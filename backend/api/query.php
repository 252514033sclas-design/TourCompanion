<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

// ---------------------------------------------------------------------
// Load configuration and shared helpers
// ---------------------------------------------------------------------
require_once __DIR__ . '/../config.php';          // $conn defined here
require_once __DIR__ . '/destination-aliases.php'; // resolveAliasMatch()

// Load existing Gemma synthesis helper (defines call_local_gemma, validators, etc.)
// This file is not modified; we reuse it exactly as-is.
if (file_exists(__DIR__ . '/../local_ai.php')) {
    require_once __DIR__ . '/../local_ai.php';
}

// ---------------------------------------------------------------------
// Only POST requests accepted
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'message' => 'Only POST requests are allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

$rawInput = file_get_contents('php://input');
$payload  = json_decode($rawInput, true);
if (!is_array($payload)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Invalid JSON payload'], JSON_UNESCAPED_UNICODE);
    exit;
}

$question = trim($payload['question'] ?? '');
$userId   = isset($payload['user_id']) ? (int)$payload['user_id'] : 0;
$userLat  = isset($payload['latitude'])  ? (float)$payload['latitude']  : null;
$userLon  = isset($payload['longitude']) ? (float)$payload['longitude'] : null;
$history  = $payload['conversation_history'] ?? [];

if ($question === '') {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Question is required'], JSON_UNESCAPED_UNICODE);
    exit;
}

// ---------------------------------------------------------------------
// Normalisation
// ---------------------------------------------------------------------
function qp_normalize(string $str): string {
    $str = mb_strtolower($str, 'UTF-8');
    $str = preg_replace('/[^\p{L}\p{M}\p{N}\s]+/u', ' ', $str);
    $str = preg_replace('/\s+/u', ' ', $str);
    return trim($str);
}

$cleanQ  = qp_normalize($question);
$paddedQ = ' ' . $cleanQ . ' ';

// ---------------------------------------------------------------------
// Haversine distance (km) — inline, no dependency on other PHP files
// ---------------------------------------------------------------------
function qp_haversine(float $lat1, float $lon1, float $lat2, float $lon2): float {
    $r    = 6371.0;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a    = sin($dLat / 2) ** 2 + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dLon / 2) ** 2;
    return $r * 2 * atan2(sqrt($a), sqrt(1 - $a));
}

// ---------------------------------------------------------------------
// Resolve transport mode word from query
// ---------------------------------------------------------------------
function qp_transport_mode(string $paddedQ): ?string {
    if (strpos($paddedQ, ' metro ') !== false || strpos($paddedQ, ' subway ') !== false)   return 'metro';
    if (strpos($paddedQ, ' bus ')   !== false || strpos($paddedQ, ' buses ') !== false)    return 'bus';
    if (strpos($paddedQ, ' train ') !== false || strpos($paddedQ, ' suburban ') !== false) return 'train';
    if (strpos($paddedQ, ' transport ') !== false || strpos($paddedQ, ' transit ') !== false) return 'bus'; // generic → bus
    return null;
}

$transportMode = qp_transport_mode($paddedQ);

// ==========================================================================
// 1️⃣  EMERGENCY
// ==========================================================================
$distressPhrases = [
    'emergency','sos','112','save me','following me','followed me','being followed',
    'chasing me','someone is following','robbed','robbery','mugged',
    'attack','attacked','assault','assaulted','kidnap','kidnapped',
    'harass','harassed','harassment','threat','threatened',
    'accident','injured','bleeding','unconscious','stalked','stalking',
    'காப்பாற்றுங்கள்','உதவி','बचाओ','मदद','आपातकाल'
];
$isEmergency = false;
foreach ($distressPhrases as $dp) {
    if (strpos($paddedQ, " $dp ") !== false || strpos($cleanQ, $dp) !== false) {
        $isEmergency = true; break;
    }
}
if (!$isEmergency && count(array_filter(explode(' ', $cleanQ))) <= 4) {
    foreach (['help','உதவி','मदद','emergency'] as $uw) {
        if (in_array($uw, explode(' ', $cleanQ), true)) { $isEmergency = true; break; }
    }
}
if ($isEmergency) {
    $emergencyAnswer = "EMERGENCY: Call 112 immediately for Police, Fire, or Ambulance services. For women's safety helpline call 1091. Move immediately to a well-lit, crowded area.";
    $stmt = $conn->prepare("SELECT answer FROM travel_tips WHERE LOWER(topic) = 'emergency' LIMIT 1");
    if ($stmt) {
        $stmt->execute();
        $row = $stmt->get_result()->fetch_assoc();
        if ($row && !empty($row['answer'])) $emergencyAnswer = $row['answer'];
        $stmt->close();
    }
    $log = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?,0), ?, ?)");
    if ($log) { $log->bind_param('iss', $userId, $question, $emergencyAnswer); $log->execute(); $log->close(); }
    echo json_encode([
        'success'               => true,
        'intent'                => 'EMERGENCY',
        'requires_gemma'        => false,
        'verified_info_available' => false,
        'topic'                 => 'Emergency',
        'verified_facts'        => new stdClass(),
        'deterministic_answer'  => $emergencyAnswer
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 2️⃣  TRANSPORT_ROUTE_SEARCH
//     Covers: Tanglish (ku/la), English "which bus/metro", "by bus/metro",
//     "how do I go … by", "route from … to", "take the/a bus/metro", etc.
// ==========================================================================

// --- English transport-route cues ---
$englishRouteCues = [
    'which metro', 'which bus', 'which train',
    'by metro', 'by bus', 'by train',
    'take the metro', 'take a metro', 'take the bus', 'take a bus',
    'take the train', 'take a train',
    'how do i go', 'how do i get', 'how to go', 'how to get',
    'route from', 'route to',
    'how can i reach', 'how do i reach',
    'go from', 'get from',
];
$hasEnglishRoute = false;
foreach ($englishRouteCues as $cue) {
    if (strpos($paddedQ, " $cue ") !== false || strpos($cleanQ, $cue) === 0 || substr($cleanQ, -strlen($cue)) === $cue) {
        $hasEnglishRoute = true; break;
    }
}

// --- Tanglish cues (ku + la with a transport mode word) ---
$hasTanglishRoute = $transportMode !== null
    && (strpos($paddedQ, ' ku ') !== false || strpos($paddedQ, ' la ') !== false);

$hasRouteSearch = $hasEnglishRoute || $hasTanglishRoute;

// If English route cue but no transport mode word yet, check additional context
if ($hasEnglishRoute && $transportMode === null) {
    // e.g. "which metro from…" already captured metro in cue string; extract from cue
    if (strpos($cleanQ, 'metro') !== false) $transportMode = 'metro';
    elseif (strpos($cleanQ, 'bus') !== false) $transportMode = 'bus';
    elseif (strpos($cleanQ, 'train') !== false) $transportMode = 'train';
}

if ($hasRouteSearch) {
    // --- Extract origin & destination from query ---
    // Pattern: "from X to Y"
    $origin      = null;
    $destination = null;
    // Strip surrounding punctuation from place names (e.g. "Chennai Central?" → "Chennai Central")
    $stripPunct = fn(string $s): string => trim($s, " \t\n\r\0\x0B.,?!:;");
    // Strip trailing transport mode phrase (e.g. "Marina Beach by bus" → "Marina Beach")
    $stripByMode = fn(string $s): string => trim(preg_replace('/\s+by\s+(bus|metro|train|subway|rail)\s*$/ui', '', $s));

    if (preg_match('/\bfrom\s+(.+?)\s+to\s+(.+?)(?:\s+by\s+\w+)?$/ui', $question, $m)) {
        $origin      = $stripPunct($m[1]);
        $destination = $stripByMode($stripPunct($m[2]));
    } elseif (preg_match('/\bfrom\s+(.+?)\s+to\s+(.+)/ui', $question, $m)) {
        $origin      = $stripPunct($m[1]);
        $destination = $stripByMode($stripPunct($m[2]));
    }

    // If no "from…to" found try alias resolution for a single destination
    $aliasSql = "SELECT a.destination_id, d.name AS destination_name, a.alias, a.language
                 FROM destination_aliases a
                 JOIN destinations d ON a.destination_id = d.id
                 WHERE d.is_active = 1
                 ORDER BY CHAR_LENGTH(a.alias) DESC";
    $aliasRes  = $conn->query($aliasSql);
    $dbAliases = [];
    while ($aliasRes && $arow = $aliasRes->fetch_assoc()) $dbAliases[] = $arow;
    $aliasMatch = resolveAliasMatch($question, $dbAliases, $conn);
    $topicName  = $aliasMatch ? ($aliasMatch['canonical_name'] ?? $aliasMatch['destination_name']) : null;

    // --- Query transport-routes DB (mirror of transport-routes.php Branch A/C) ---
    $routeData = [];
    if ($origin !== null && $destination !== null) {
        // Reuse resolveStopIds logic inline
        $resolveStop = function(string $name) use ($conn): array {
            $lower = strtolower(trim($name));
            if ($lower === '') return [];
            $ids = [];
            foreach (['SELECT stop_id FROM transport_stop_aliases WHERE LOWER(alias) = ?',
                      'SELECT stop_id FROM transport_stops WHERE LOWER(stop_name) = ?'] as $q2) {
                $st = $conn->prepare($q2);
                if ($st) {
                    $st->bind_param('s', $lower); $st->execute();
                    $r2 = $st->get_result();
                    while ($rr = $r2->fetch_row()) $ids[(string)$rr[0]] = true;
                    $st->close();
                }
                if (!empty($ids)) break;
            }
            if (empty($ids)) {
                $pat = '%' . $lower . '%';
                $st  = $conn->prepare('SELECT stop_id FROM transport_stops WHERE LOWER(stop_name) LIKE ? LIMIT 25');
                if ($st) {
                    $st->bind_param('s', $pat); $st->execute();
                    $r2 = $st->get_result();
                    while ($rr = $r2->fetch_row()) $ids[(string)$rr[0]] = true;
                    $st->close();
                }
            }
            // Expand parent stations
            if (!empty($ids)) {
                $seed = array_keys($ids);
                $ph   = implode(',', array_fill(0, count($seed), '?'));
                $st   = $conn->prepare("SELECT stop_id FROM transport_stops WHERE parent_station IN ($ph)");
                if ($st) {
                    $st->bind_param(str_repeat('s', count($seed)), ...$seed);
                    $st->execute();
                    $r2 = $st->get_result();
                    while ($rr = $r2->fetch_row()) $ids[(string)$rr[0]] = true;
                    $st->close();
                }
            }
            return array_keys($ids);
        };

        $origIds = $resolveStop($origin);
        $destIds = $resolveStop($destination);

        if (!empty($origIds) && !empty($destIds)) {
            $phO  = implode(',', array_fill(0, count($origIds), '?'));
            $phD  = implode(',', array_fill(0, count($destIds), '?'));
            $params = array_merge($origIds, $destIds);
            $types  = str_repeat('s', count($origIds) + count($destIds));

            // Mode filter
            $modeClause = '';
            if ($transportMode !== null) {
                $modeMap = ['bus' => 3, 'metro' => 1, 'train' => 2];
                if (isset($modeMap[$transportMode])) {
                    $modeClause = ' AND r.route_type = ' . $modeMap[$transportMode];
                }
            }

            $sql = "SELECT r.route_id, r.agency_id,
                           COALESCE(a.agency_name, CASE WHEN r.route_id LIKE 'CMRL-%' THEN 'Chennai Metro Rail Limited' ELSE NULL END) AS agency_name,
                           r.route_short_name, r.route_long_name, r.route_type,
                           r.route_desc, r.route_color, r.route_text_color,
                           MIN(dest.stop_sequence - orig.stop_sequence) AS stop_hops
                    FROM transport_route_stops orig
                    JOIN transport_route_stops dest
                      ON orig.route_id = dest.route_id
                     AND orig.direction_id = dest.direction_id
                     AND orig.stop_sequence < dest.stop_sequence
                    JOIN transport_routes r ON r.route_id = orig.route_id
                    LEFT JOIN transport_agencies a ON r.agency_id = a.agency_id
                    WHERE orig.stop_id IN ($phO) AND dest.stop_id IN ($phD)$modeClause
                    GROUP BY r.route_id, r.agency_id, agency_name, r.route_short_name,
                             r.route_long_name, r.route_type, r.route_desc, r.route_color, r.route_text_color
                    ORDER BY stop_hops ASC, r.route_short_name ASC
                    LIMIT 50";
            $st = $conn->prepare($sql);
            if ($st) {
                $st->bind_param($types, ...$params);
                $st->execute();
                $res = $st->get_result();
                while ($row = $res->fetch_assoc()) {
                    $routeData[] = [
                        'route_id'         => (string)$row['route_id'],
                        'agency_id'        => $row['agency_id'] !== null ? (string)$row['agency_id'] : null,
                        'agency_name'      => $row['agency_name'] !== null ? (string)$row['agency_name'] : null,
                        'route_short_name' => (string)$row['route_short_name'],
                        'route_long_name'  => (string)$row['route_long_name'],
                        'route_type'       => (int)$row['route_type'],
                        'route_desc'       => $row['route_desc'] !== null && $row['route_desc'] !== '' ? (string)$row['route_desc'] : null,
                        'route_color'      => $row['route_color'] !== null && $row['route_color'] !== '' ? (string)$row['route_color'] : null,
                        'route_text_color' => $row['route_text_color'] !== null && $row['route_text_color'] !== '' ? (string)$row['route_text_color'] : null,
                    ];
                }
                $st->close();
            }
        }
    }

    $hasVerifiedRoutes = !empty($routeData);
    if ($hasVerifiedRoutes) {
        $r0  = $routeData[0];
        $det = "Take " . ($r0['route_short_name'] ?? $r0['route_long_name']) . " (" . ($r0['agency_name'] ?? '') . ").";
        if (count($routeData) > 1) $det .= ' Also: ' . ($routeData[1]['route_short_name'] ?? '');
    } else {
        $det = $topicName
            ? "Transport route information for $topicName could not be resolved from verified data."
            : "Transport route information could not be resolved from verified data.";
    }

    echo json_encode([
        'success'               => true,
        'intent'                => 'TRANSPORT_ROUTE_SEARCH',
        'requires_gemma'        => false,
        'verified_info_available' => $hasVerifiedRoutes,
        'topic'                 => $topicName ?? ($origin && $destination ? "$origin to $destination" : null),
        'origin'                => $origin,
        'destination'           => $destination,
        'transport_mode'        => $transportMode,
        'verified_facts'        => ['routes' => $routeData],
        'deterministic_answer'  => $det,
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 3️⃣  TRANSPORT_TIMING  (placeholder – no change from previous behaviour)
// ==========================================================================
// (no timing phrases detected here; would sit between route-search and nearby)

// ==========================================================================
// 4️⃣  TRANSPORT_NEARBY
//     Must come BEFORE generic NEARBY_ATTRACTIONS
// ==========================================================================
$transportNearbyPhrases = [
    'metro near me',   'bus near me',    'train near me',
    'nearest metro',   'nearest bus',    'nearest train',
    'nearest bus stop','nearest metro station','nearest train station',
    'nearby metro',    'nearby bus',     'nearby train',
    'metro station near me', 'bus stop near me', 'train station near me',
    'metro stops near me',   'bus stops near me',
    'where is the nearest metro', 'where is the nearest bus',
    'where is the nearest train',
];
$hasTransportNearby = false;
foreach ($transportNearbyPhrases as $tnp) {
    if (strpos($paddedQ, " $tnp ") !== false || strpos($cleanQ, $tnp) !== false) {
        $hasTransportNearby = true; break;
    }
}

if ($hasTransportNearby) {
    $requiresLocation = ($userLat === null || $userLon === null);
    if ($requiresLocation) {
        echo json_encode([
            'success'               => true,
            'intent'                => 'TRANSPORT_NEARBY',
            'requires_gemma'        => false,
            'verified_info_available' => false,
            'topic'                 => null,
            'verified_facts'        => new stdClass(),
            'deterministic_answer'  => 'Location permission is required to find nearby transport stops.',
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // Determine mode from query
    $nearbyMode = null;
    if (strpos($cleanQ, 'metro') !== false || strpos($cleanQ, 'subway') !== false) $nearbyMode = 'metro';
    elseif (strpos($cleanQ, 'bus') !== false) $nearbyMode = 'bus';
    elseif (strpos($cleanQ, 'train') !== false || strpos($cleanQ, 'suburban') !== false) $nearbyMode = 'train';

    // --- Inline port of transport-nearby.php DB logic ---
    $radiusKm = 2.0;
    $lat      = $userLat;
    $lon      = $userLon;
    $latDelta = $radiusKm / 111.045;
    $cosLat   = cos(deg2rad($lat));
    $lonDelta = $radiusKm / (111.045 * max(abs($cosLat), 0.001));

    $minLat = $lat - $latDelta; $maxLat = $lat + $latDelta;
    $minLon = $lon - $lonDelta; $maxLon = $lon + $lonDelta;

    $whereClauses = ["stop_lat BETWEEN ? AND ?", "stop_lon BETWEEN ? AND ?"];
    $params       = [$lat, $lon, $lat, $minLat, $maxLat, $minLon, $maxLon];
    $types        = "ddddddd";

    if ($nearbyMode !== null) {
        if ($nearbyMode === 'metro')   { $whereClauses[] = "stop_id LIKE 'CMRL-%'"; }
        elseif ($nearbyMode === 'bus') { $whereClauses[] = "stop_id LIKE 'MTC-%'"; }
        elseif ($nearbyMode === 'train') { $whereClauses[] = "(stop_id LIKE 'SR-%' OR stop_id LIKE 'SRR-%')"; }
    }

    $whereSql = implode(' AND ', $whereClauses);
    $sql = "SELECT stop_id, stop_name, stop_lat, stop_lon, location_type, parent_station,
                   ROUND((6371 * ACOS(
                       LEAST(1.0, GREATEST(-1.0,
                           COS(RADIANS(?)) * COS(RADIANS(stop_lat)) * COS(RADIANS(stop_lon) - RADIANS(?)) +
                           SIN(RADIANS(?)) * SIN(RADIANS(stop_lat))
                       ))
                   )), 3) AS distance_km
            FROM transport_stops
            WHERE {$whereSql}
            HAVING distance_km <= ?
            ORDER BY distance_km ASC
            LIMIT 50";
    $params[] = $radiusKm;
    $types   .= "d";

    $stopData = [];
    $st = $conn->prepare($sql);
    if ($st) {
        $st->bind_param($types, ...$params);
        $st->execute();
        $res = $st->get_result();
        while ($row = $res->fetch_assoc()) {
            $sid = (string)$row['stop_id'];
            $agency = null;
            if (strpos($sid, 'MTC-')  === 0) $agency = ['agency_id' => '69',   'agency_name' => 'MTC',  'agency_full_name' => 'Metropolitan Transport Corporation'];
            elseif (strpos($sid, 'CMRL-') === 0) $agency = ['agency_id' => 'CMRL','agency_name' => 'CMRL', 'agency_full_name' => 'Chennai Metro Rail Limited'];
            elseif (strpos($sid, 'SR-')   === 0 || strpos($sid, 'SRR-') === 0) $agency = ['agency_id' => 'SR','agency_name' => 'Southern Railway','agency_full_name' => 'Southern Railway'];
            $stopData[] = [
                'stop_id'        => $sid,
                'stop_name'      => (string)$row['stop_name'],
                'stop_lat'       => (float)$row['stop_lat'],
                'stop_lon'       => (float)$row['stop_lon'],
                'location_type'  => (int)$row['location_type'],
                'parent_station' => ($row['parent_station'] !== null && $row['parent_station'] !== '') ? (string)$row['parent_station'] : null,
                'distance_km'    => (float)$row['distance_km'],
                'agency'         => $agency,
            ];
        }
        $st->close();
    }

    $count = count($stopData);
    if ($count > 0) {
        $nearest = $stopData[0];
        $det = "The nearest " . ($nearbyMode ?? 'transport') . " stop is {$nearest['stop_name']} ({$nearest['distance_km']} km away).";
        if ($count > 1) $det .= " Also nearby: {$stopData[1]['stop_name']} ({$stopData[1]['distance_km']} km).";
    } else {
        $det = "No " . ($nearbyMode ?? 'transport') . " stops found within {$radiusKm} km of your location.";
    }

    echo json_encode([
        'success'               => true,
        'intent'                => 'TRANSPORT_NEARBY',
        'requires_gemma'        => false,
        'verified_info_available' => $count > 0,
        'topic'                 => null,
        'transport_mode'        => $nearbyMode,
        'verified_facts'        => ['stops' => $stopData, 'count' => $count, 'radius_km' => $radiusKm],
        'deterministic_answer'  => $det,
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 4️⃣b DESTINATION_DISCOVERY
//     Generic discovery of verified Chennai destinations.
//     Must execute BEFORE NEARBY_ATTRACTIONS so generic questions like
//     "What places can I visit in Chennai?" do NOT require GPS coordinates.
// ==========================================================================
$proximityCues = [
    'near me', 'nearby', 'near by', 'near my', 'near here',
    'around me', 'around here', 'around us', 'close to me', 'close by', 'closest'
];
$hasProximity = false;
foreach ($proximityCues as $pc) {
    if (strpos($cleanQ, $pc) !== false) {
        $hasProximity = true;
        break;
    }
}

$unsupportedPlanningCues = [
    'peaceful', 'quiet', 'calm', 'relaxing', 'romantic',
    'hour', 'hours', 'half day', 'minute', 'minutes'
];
$hasUnsupportedPlanning = false;
foreach ($unsupportedPlanningCues as $upc) {
    if (strpos($cleanQ, $upc) !== false) {
        $hasUnsupportedPlanning = true;
        break;
    }
}

$discoveryPhrases = [
    'places to visit', 'places to see', 'places to explore', 'places to go',
    'interesting places', 'tourist attractions', 'places in chennai',
    'what to see', 'suggest places', 'where should i go', 'where can i go',
    'top places', 'best places', 'tourist spots', 'must visit',
    'sightseeing', 'what places', 'which places', 'where can i visit',
    'what can i visit', 'places can i visit', 'explore in chennai',
    'explore chennai', 'visit in chennai', 'visit chennai'
];

$isDiscovery = false;
if (!$hasProximity && !$hasUnsupportedPlanning) {
    foreach ($discoveryPhrases as $dp) {
        if (strpos($paddedQ, " $dp ") !== false || strpos($cleanQ, $dp) !== false) {
            $isDiscovery = true;
            break;
        }
    }
}

if ($isDiscovery) {
    $discoveryThemes = [
        'Beach'    => "category LIKE '%Beach%'",
        'Temple'   => "category LIKE '%Temple%'",
        'Heritage' => "category LIKE '%Heritage%' OR category = 'Culture'",
        'Museum'   => "category LIKE '%Museum%'",
        'Nature'   => "category LIKE '%Nature%' OR category LIKE '%Park%' OR category = 'Botanical Garden'",
    ];

    $chosenDestinations = [];
    $usedIds = [];

    foreach ($discoveryThemes as $theme => $cond) {
        $excludeClause = !empty($usedIds) ? " AND id NOT IN (" . implode(',', $usedIds) . ")" : "";
        $stmt = $conn->prepare("SELECT id, name, category, area, overview 
                                FROM destinations 
                                WHERE is_active = 1 AND city = 'Chennai' AND ($cond)$excludeClause 
                                ORDER BY id ASC LIMIT 1");
        if ($stmt) {
            $stmt->execute();
            $row = $stmt->get_result()->fetch_assoc();
            if ($row) {
                $chosenDestinations[] = [
                    'id'       => (int)$row['id'],
                    'name'     => (string)$row['name'],
                    'category' => (string)$row['category'],
                    'area'     => (string)($row['area'] ?? 'Chennai'),
                    'overview' => (string)($row['overview'] ?? '')
                ];
                $usedIds[] = (int)$row['id'];
            }
            $stmt->close();
        }
    }

    $detLines = ["Here are verified places you can visit in Chennai:"];
    foreach ($chosenDestinations as $d) {
        $detLines[] = "• {$d['name']} ({$d['category']} in {$d['area']})";
    }
    $deterministicAnswer = implode("\n", $detLines);

    // Build verified fact block for Gemma
    $factLines = [
        "Verified Destinations in Chennai (These are the only verified destinations available for this response. Do not add destinations that are not listed):"
    ];
    foreach ($chosenDestinations as $d) {
        $factLines[] = "• {$d['name']} | Category: {$d['category']} | Area: {$d['area']} | Overview: {$d['overview']}";
    }
    $verifiedFactBlock = implode("\n", $factLines);

    $gemmaAnswer    = null;
    $gemmaUsed      = false;
    $gemmaAvailable = function_exists('call_local_gemma') && function_exists('is_local_ai_available') && is_local_ai_available();

    if ($gemmaAvailable && !empty($chosenDestinations)) {
        $rawAnswer = call_local_gemma(
            $question,
            "Chennai Destinations",
            $verifiedFactBlock,
            ['timeout' => 15, 'max_tokens' => 120],
            $history
        );
        if ($rawAnswer !== null && trim($rawAnswer) !== '') {
            $gemmaAnswer = trim($rawAnswer);
            $gemmaUsed   = true;
        }
    }

    $finalAnswer = ($gemmaUsed && $gemmaAnswer !== null) ? $gemmaAnswer : $deterministicAnswer;

    echo json_encode([
        'success'                 => true,
        'intent'                  => 'DESTINATION_DISCOVERY',
        'requires_gemma'          => true,
        'verified_info_available' => true,
        'topic'                   => 'Chennai Destinations',
        'verified_facts'          => [
            'count'        => count($chosenDestinations),
            'destinations' => $chosenDestinations
        ],
        'gemma_available'         => $gemmaAvailable,
        'gemma_used'              => $gemmaUsed,
        'answer'                  => $finalAnswer,
        'deterministic_answer'    => $deterministicAnswer,
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 5️⃣  NEARBY_ATTRACTIONS
// ==========================================================================
$nearbyAttractionPhrases = [
    'places near me', 'places nearby', 'attractions near me', 'attractions nearby',
    'what is near me', 'what are near me', 'what places near me', 'places to visit nearby',
    'places to visit near me', 'what can i visit near me', 'nearby attractions',
    'near me', 'around here', 'close to me', 'closest place', 'closest attraction',
];
$hasNearbyAttractions = false;
foreach ($nearbyAttractionPhrases as $nap) {
    if (strpos($paddedQ, " $nap ") !== false || strpos($cleanQ, $nap) !== false) {
        $hasNearbyAttractions = true; break;
    }
}

if ($hasNearbyAttractions) {
    $requiresLocation = ($userLat === null || $userLon === null);
    if ($requiresLocation) {
        echo json_encode([
            'success'               => true,
            'intent'                => 'NEARBY_ATTRACTIONS',
            'requires_gemma'        => false,
            'verified_info_available' => false,
            'topic'                 => null,
            'verified_facts'        => new stdClass(),
            'deterministic_answer'  => 'Location permission is required to find nearby attractions.',
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // --- Inline port of destinations-nearby.php DB logic ---
    $lat      = $userLat;
    $lon      = $userLon;
    $radiusKm = 15.0;
    $limit    = 3;

    $st = $conn->prepare("SELECT id, name, city, category, latitude, longitude, overview, timings, entry_fee
                           FROM destinations
                           WHERE is_active = 1 AND latitude IS NOT NULL AND longitude IS NOT NULL");
    $nearbyList = [];
    if ($st) {
        $st->execute();
        $res = $st->get_result();
        while ($row = $res->fetch_assoc()) {
            $dLat = (float)$row['latitude'];
            $dLon = (float)$row['longitude'];
            $dist = qp_haversine($lat, $lon, $dLat, $dLon);
            if ($dist <= $radiusKm) {
                $nearbyList[] = [
                    'id'          => (int)$row['id'],
                    'name'        => $row['name'],
                    'city'        => $row['city'],
                    'category'    => $row['category'],
                    'latitude'    => $dLat,
                    'longitude'   => $dLon,
                    'distance_km' => round($dist, 1),
                    'overview'    => $row['overview'],
                    'timings'     => $row['timings'],
                ];
            }
        }
        $st->close();
    }
    usort($nearbyList, fn($a, $b) => $a['distance_km'] <=> $b['distance_km']);
    $topDest = array_slice($nearbyList, 0, $limit);
    $found   = count($topDest);

    if ($found === 0) {
        $det = "No Chennai tourist attractions were found within " . round($radiusKm) . " km of your location.";
    } else {
        $lines = [];
        foreach ($topDest as $i => $d) $lines[] = ($i + 1) . ". {$d['name']} (about {$d['distance_km']} km from you).";
        $det = "Nearby attractions: " . implode(' ', $lines);
    }

    echo json_encode([
        'success'               => true,
        'intent'                => 'NEARBY_ATTRACTIONS',
        'requires_gemma'        => false,
        'verified_info_available' => $found > 0,
        'topic'                 => 'Nearby Attractions',
        'verified_facts'        => [
            'count'        => $found,
            'radius_km'    => $radiusKm,
            'user_coords'  => ['latitude' => $lat, 'longitude' => $lon],
            'destinations' => $topDest,
        ],
        'deterministic_answer'  => $det,
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 6️⃣  DISTANCE_DIRECTIONS
//     Detect English "how far", "distance to", "how do I reach", etc.
//     AND Tanglish "ku" without a transport mode.
//     Explicit distance intent wins over destination alias match.
// ==========================================================================
$distanceCuesEnglish = [
    'how far', 'distance to', 'distance from', 'directions to',
    'how do i reach', 'how can i reach', 'how do i get to',
    'how to reach', 'how to get to', 'get to', 'reach',
];
$hasDistanceCueEnglish = false;
foreach ($distanceCuesEnglish as $dc) {
    if (strpos($paddedQ, " $dc ") !== false || strpos($cleanQ, $dc) === 0) {
        $hasDistanceCueEnglish = true; break;
    }
}
$hasDistanceCueTamil = strpos($paddedQ, ' ku ') !== false && $transportMode === null;
$hasDistance = $hasDistanceCueEnglish || $hasDistanceCueTamil;

if ($hasDistance) {
    // Resolve destination from alias table
    $aliasSql  = "SELECT a.destination_id, d.name AS destination_name, a.alias, a.language
                  FROM destination_aliases a
                  JOIN destinations d ON a.destination_id = d.id
                  WHERE d.is_active = 1
                  ORDER BY CHAR_LENGTH(a.alias) DESC";
    $aliasRes  = $conn->query($aliasSql);
    $dbAliases = [];
    while ($aliasRes && $arow = $aliasRes->fetch_assoc()) $dbAliases[] = $arow;
    $aliasMatch = resolveAliasMatch($question, $dbAliases, $conn);

    $matchedDestId = $aliasMatch ? $aliasMatch['destination_id'] : null;

    if ($matchedDestId !== null) {
        $st = $conn->prepare("SELECT name, latitude, longitude FROM destinations WHERE id = ? AND is_active = 1");
        $st->bind_param('i', $matchedDestId);
        $st->execute();
        $row = $st->get_result()->fetch_assoc();
        $st->close();

        $topicName = $row['name'] ?? null;
        $destLat   = isset($row['latitude'])  ? (float)$row['latitude']  : null;
        $destLon   = isset($row['longitude']) ? (float)$row['longitude'] : null;

        $requiresLocation = ($userLat === null || $userLon === null);
        if ($requiresLocation) {
            $det = "Location permission is required to calculate the distance to $topicName.";
            $verifiedAvail = false;
        } elseif ($destLat !== null && $destLon !== null) {
            $distKm = qp_haversine($userLat, $userLon, $destLat, $destLon);
            $distStr = number_format(round($distKm, 1), 1, '.', '');
            if ($distKm > 100.0) {
                $det = "A verified Chennai destination distance is unavailable from your current location.";
            } elseif ($distKm <= 0.2) {
                $det = "You are at $topicName right now.";
            } else {
                $det = "$topicName is about $distStr km from your current location.";
            }
            $verifiedAvail = true;
        } else {
            $det = "Coordinates for $topicName are not available in the database.";
            $verifiedAvail = false;
        }

        echo json_encode([
            'success'               => true,
            'intent'                => 'DISTANCE_DIRECTIONS',
            'requires_gemma'        => false,
            'verified_info_available' => $verifiedAvail,
            'topic'                 => $topicName,
            'verified_facts'        => $verifiedAvail && isset($distKm) ? [
                'destination'      => $topicName,
                'distance_km'      => round($distKm, 1),
                'dest_latitude'    => $destLat,
                'dest_longitude'   => $destLon,
                'user_latitude'    => $userLat,
                'user_longitude'   => $userLon,
            ] : new stdClass(),
            'deterministic_answer'  => $det,
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // No destination resolved — still return DISTANCE_DIRECTIONS
    echo json_encode([
        'success'               => true,
        'intent'                => 'DISTANCE_DIRECTIONS',
        'requires_gemma'        => false,
        'verified_info_available' => false,
        'topic'                 => null,
        'verified_facts'        => new stdClass(),
        'deterministic_answer'  => "I couldn't identify a verified Chennai destination in that query.",
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 7️⃣  DESTINATION_QUERY
// ==========================================================================
$matchedDestId = null;
$matchedName   = null;

$aliasSql  = "SELECT a.destination_id, d.name AS destination_name, a.alias, a.language
              FROM destination_aliases a
              JOIN destinations d ON a.destination_id = d.id
              WHERE d.is_active = 1
              ORDER BY CHAR_LENGTH(a.alias) DESC";
$aliasRes  = $conn->query($aliasSql);
$dbAliases = [];
while ($aliasRes && $arow = $aliasRes->fetch_assoc()) $dbAliases[] = $arow;
$aliasMatch = resolveAliasMatch($question, $dbAliases, $conn);
if ($aliasMatch !== null) {
    $matchedDestId = $aliasMatch['destination_id'];
    $matchedName   = $aliasMatch['canonical_name'] ?? $aliasMatch['destination_name'];
}

// Fallback hard-coded pattern list
if ($matchedDestId === null) {
    $patterns = [
        1 => ['marina beach','marina','மெரினா','மரினா','मरीना बीच','मरीना'],
        2 => ['kapaleeshwarar','kapaleeswarar','kapaleeshwar','mylapore temple','கபாலீஸ்வரர்','कपालेश्वर','कपालीश्वर'],
        3 => ['fort st george','fort st. george','st mary','st. mary','புனித ஜார்ஜ்','फोर्ट सेंट जॉर्ज'],
        4 => ['government museum','egmore museum','national art gallery','அருங்காட்சியகம்','எழும்பூர் அருங்காட்சியகம்','एग्मोर संग्रहालय','संग्रहालय'],
        5 => ['besant nagar beach','besant nagar','elliot','elliots','edward elliot','பெசன்ட் நகர்','इलियट्स बीच','इलियट्स'],
    ];
    foreach ($patterns as $id => $keys) {
        foreach ($keys as $k) {
            if (mb_strpos($cleanQ, $k, 0, 'UTF-8') !== false) {
                $matchedDestId = $id;
                $matchedName   = $k;
                break 2;
            }
        }
    }
}

if ($matchedDestId !== null) {
    $st = $conn->prepare("SELECT name, overview, timings, entry_fee, things_to_do, safety_tips FROM destinations WHERE id = ? AND is_active = 1");
    $st->bind_param('i', $matchedDestId);
    $st->execute();
    $row = $st->get_result()->fetch_assoc();
    $st->close();

    $facts = [];
    if ($row) {
        $facts = [
            'name'        => $row['name']        ?? null,
            'overview'    => $row['overview']     ?? null,
            'timings'     => $row['timings']      ?? null,
            'entry_fee'   => $row['entry_fee']    ?? null,
            'things_to_do'=> $row['things_to_do'] ?? null,
            'safety_tips' => $row['safety_tips']  ?? null,
        ];
    }

    $hasFoodCue = (bool)preg_match('/\bfood\b|\bmeal\b|\brestaurant\b/i', $question);
    if ($hasFoodCue) {
        echo json_encode([
            'success'               => true,
            'intent'                => 'DESTINATION_QUERY',
            'requires_gemma'        => false,
            'verified_info_available' => false,
            'topic'                 => $matchedName,
            'verified_facts'        => new stdClass(),
            'deterministic_answer'  => "I don't have verified information about food there yet.",
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // -----------------------------------------------------------------
    // Gemma synthesis — DESTINATION_QUERY with verified facts
    // Gemma is ONLY called here; all other intents remain deterministic.
    // If Gemma is unavailable or validation fails, fall back to facts.
    // -----------------------------------------------------------------
    $gemmaAnswer   = null;
    $gemmaUsed     = false;
    $gemmaAvailable = function_exists('call_local_gemma') && function_exists('is_local_ai_available') && is_local_ai_available();

    if ($gemmaAvailable && !empty($facts)) {
        // Build a compact verified-facts block (only fields that are populated)
        $factLines = [];
        if (!empty($facts['name']))         $factLines[] = "Name: " . $facts['name'];
        if (!empty($facts['overview']))     $factLines[] = "Overview: " . $facts['overview'];
        if (!empty($facts['timings']))      $factLines[] = "Timings: " . $facts['timings'];
        if (!empty($facts['entry_fee']))    $factLines[] = "Entry Fee: " . $facts['entry_fee'];
        if (!empty($facts['things_to_do'])) $factLines[] = "Things to Do: " . $facts['things_to_do'];
        if (!empty($facts['safety_tips']))  $factLines[] = "Safety Tips: " . $facts['safety_tips'];

        $verifiedFactBlock = implode("\n", $factLines);

        // call_local_gemma handles: language detection, prompt building,
        // grounding validation, language-script validation, retry logic.
        // It returns null on any failure — we then use the deterministic facts.
        $rawAnswer = call_local_gemma(
            $question,
            (string)($facts['name'] ?? $matchedName),
            $verifiedFactBlock,
            ['timeout' => 15, 'max_tokens' => 60],
            $history
        );

        if ($rawAnswer !== null && trim($rawAnswer) !== '') {
            $gemmaAnswer = trim($rawAnswer);
            $gemmaUsed   = true;
        }
    }

    echo json_encode([
        'success'               => true,
        'intent'                => 'DESTINATION_QUERY',
        'requires_gemma'        => true,
        'verified_info_available' => true,
        'topic'                 => $matchedName,
        'verified_facts'        => $facts,
        'gemma_available'       => $gemmaAvailable,
        'gemma_used'            => $gemmaUsed,
        'answer'                => $gemmaAnswer,                // null if Gemma unavailable/failed
        'deterministic_answer'  => $gemmaUsed ? null : null,   // facts are always in verified_facts
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==========================================================================
// 8️⃣  UNKNOWN
// ==========================================================================
echo json_encode([
    'success'               => true,
    'intent'                => 'UNKNOWN',
    'requires_gemma'        => false,
    'verified_info_available' => false,
    'topic'                 => null,
    'verified_facts'        => new stdClass(),
    'deterministic_answer'  => null,
], JSON_UNESCAPED_UNICODE);
?>
