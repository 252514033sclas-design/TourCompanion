<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

// Include local AI helper for synthesis
if (file_exists(__DIR__ . '/../local_ai.php')) {
    require_once __DIR__ . '/../local_ai.php';
} elseif (file_exists(__DIR__ . '/local_ai.php')) {
    require_once __DIR__ . '/local_ai.php';
}

// Method validation: only GET requests are allowed; POST returns 405 Method Not Allowed
if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode([
        "status" => "error",
        "message" => "Only GET requests are allowed"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * Calculates straight-line Haversine distance in kilometers between two coordinates.
 */
function haversine_km(float $lat1, float $lon1, float $lat2, float $lon2): float {
    $earthRadiusKm = 6371.0;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a = sin($dLat / 2) * sin($dLat / 2) +
         cos(deg2rad($lat1)) * cos(deg2rad($lat2)) *
         sin($dLon / 2) * sin($dLon / 2);
    $c = 2 * atan2(sqrt($a), sqrt(1 - $a));
    return $earthRadiusKm * $c;
}

// Read parameters
$lat = null;
$lon = null;
$q = '';

if (isset($_GET['lat'])) $lat = (float)$_GET['lat'];
elseif (isset($_GET['latitude'])) $lat = (float)$_GET['latitude'];

if (isset($_GET['lon'])) $lon = (float)$_GET['lon'];
elseif (isset($_GET['longitude'])) $lon = (float)$_GET['longitude'];

if (isset($_GET['q'])) $q = trim((string)$_GET['q']);
elseif (isset($_GET['destination'])) $q = trim((string)$_GET['destination']);

// 1. Validation: valid coordinates are required
if ($lat === null || $lon === null || is_nan($lat) || is_nan($lon) || is_infinite($lat) || is_infinite($lon)) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Valid latitude and longitude coordinates are required."
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($lat < -90.0 || $lat > 90.0 || $lon < -180.0 || $lon > 180.0) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Coordinates out of bounds. Latitude must be in [-90, 90] and longitude in [-180, 180]."
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($q === '') {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Destination query 'q' is required."
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// 2. Fetch all active destinations with verified coordinates
$stmt = $conn->prepare("SELECT id, name, city, category, latitude, longitude, search_tags, overview, timings FROM destinations WHERE is_active = 1 AND latitude IS NOT NULL AND longitude IS NOT NULL");
if (!$stmt) {
    http_response_code(500);
    echo json_encode(["status" => "error", "message" => "Database query preparation failed."], JSON_UNESCAPED_UNICODE);
    exit;
}

$stmt->execute();
$result = $stmt->get_result();

$destinations = [];
while ($row = $result->fetch_assoc()) {
    $destinations[] = $row;
}
$stmt->close();

// 3. Resolve target destination
function resolve_destination(string $query, array $destinations): ?array {
    $lower = mb_strtolower(trim($query), 'UTF-8');

    // Canonical aliases with known IDs
    $aliases = [
        1 => ['marina beach', 'marina', 'மெரினா', 'மரினா', 'मरीना बीच', 'मरीना'],
        2 => ['kapaleeshwarar', 'kapaleeswarar', 'kapaleeswar', 'mylapore temple', 'arulmigu kapaleeshwarar', 'கபாலீஸ்வரர்', 'कपालेश्वर', 'कपालीश्वर'],
        3 => ['fort st george', 'fort st. george', 'fort museum', "st mary's church", 'st marys church', 'st mary', 'புனித ஜார்ஜ்', 'फोर्ट सेंट जॉर्ज'],
        4 => ['government museum', 'egmore museum', 'madras museum', 'national art gallery', 'அருங்காட்சியகம்', 'எழும்பூர் அருங்காட்சியகம்', 'एग्मोर संग्रहालय'],
        5 => ['besant nagar beach', 'besant nagar', "elliot's beach", 'elliots beach', 'elliot beach', 'bessy beach', 'பெசன்ட் நகர்', 'इलियट्स बीच', 'इलियट्स']
    ];

    foreach ($aliases as $id => $aliasList) {
        foreach ($aliasList as $alias) {
            if (mb_strpos($lower, $alias) !== false) {
                foreach ($destinations as $d) {
                    if ((int)$d['id'] === $id) return $d;
                }
            }
        }
    }

    // Secondary search across destination names and search tags
    foreach ($destinations as $d) {
        $nameLower = mb_strtolower($d['name'], 'UTF-8');
        if (mb_strpos($lower, $nameLower) !== false || mb_strpos($nameLower, $lower) !== false) {
            return $d;
        }
        if (!empty($d['search_tags'])) {
            $tags = array_map('trim', explode(',', mb_strtolower($d['search_tags'], 'UTF-8')));
            foreach ($tags as $tag) {
                if ($tag !== '' && (mb_strpos($lower, $tag) !== false || mb_strpos($tag, $lower) !== false)) {
                    return $d;
                }
            }
        }
    }

    return null;
}

$matchedDest = resolve_destination($q, $destinations);

// If the destination cannot be resolved: deterministic no-result response
if ($matchedDest === null) {
    echo json_encode([
        "status" => "no_result",
        "id" => null,
        "destination_id" => null,
        "destination" => null,
        "name" => null,
        "city" => null,
        "latitude" => null,
        "longitude" => null,
        "distance_km" => null,
        "user_coords" => ["latitude" => $lat, "longitude" => $lon],
        "topic" => "Destination Distance",
        "answer" => "I could not find a verified Chennai destination matching '$q'.",
        "ai_generated" => false
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// 4. Calculate exact Haversine distance
$destLat = (float)$matchedDest['latitude'];
$destLon = (float)$matchedDest['longitude'];
$distKm = haversine_km($lat, $lon, $destLat, $destLon);
$roundedDist = round($distKm, 1);
$distStr = number_format($roundedDist, 1, '.', '');

// 5. Check if user is outside Chennai region (> 100 km)
// e.g. Mountain View (37.421998, -122.084000) or other regions
$maxServiceRadiusKm = 100.0;
if ($distKm > $maxServiceRadiusKm) {
    $unavailableMsg = "A verified Chennai destination distance is unavailable from your current location.";
    echo json_encode([
        "status" => "ok",
        "id" => (int)$matchedDest['id'],
        "destination_id" => (int)$matchedDest['id'],
        "name" => $matchedDest['name'],
        "destination" => $matchedDest['name'],
        "city" => $matchedDest['city'],
        "latitude" => $destLat,
        "longitude" => $destLon,
        "distance_km" => $roundedDist,
        "is_in_range" => false,
        "user_coords" => ["latitude" => $lat, "longitude" => $lon],
        "topic" => $matchedDest['name'],
        "answer" => $unavailableMsg,
        "ai_generated" => false
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// 6. Format deterministic response
if ($roundedDist <= 0.2) {
    $deterministicAnswer = "You're at {$matchedDest['name']} right now.";
} else {
    $deterministicAnswer = "{$matchedDest['name']} is about {$distStr} km from your current location.";
}

// 7. Natural-language synthesis with local Gemma (if available)
$finalAnswer = $deterministicAnswer;
$isAi = false;

if (function_exists('call_local_gemma')) {
    $factPrompt = "Destination: {$matchedDest['name']}\n" .
                  "Verified Distance: {$distStr} km from user's current location\n" .
                  "Category: {$matchedDest['category']}\n" .
                  "Overview: {$matchedDest['overview']}\n\n" .
                  "INSTRUCTIONS: Answer how far the destination is from the user using ONLY the verified distance ({$distStr} km). " .
                  "Do NOT calculate distance. Do NOT change the number. Do NOT give travel directions or routes.";

    $aiAnswer = call_local_gemma($q, $matchedDest['name'], $factPrompt, [
        'timeout' => 12
    ]);

    if ($aiAnswer !== null && trim($aiAnswer) !== '') {
        $finalAnswer = trim($aiAnswer);
        $isAi = true;
    }
}

// 8. Return response
echo json_encode([
    "status" => "ok",
    "id" => (int)$matchedDest['id'],
    "destination_id" => (int)$matchedDest['id'],
    "name" => $matchedDest['name'],
    "destination" => $matchedDest['name'],
    "city" => $matchedDest['city'],
    "latitude" => $destLat,
    "longitude" => $destLon,
    "distance_km" => $roundedDist,
    "is_in_range" => true,
    "user_coords" => ["latitude" => $lat, "longitude" => $lon],
    "topic" => $matchedDest['name'],
    "answer" => $finalAnswer,
    "ai_generated" => $isAi
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
