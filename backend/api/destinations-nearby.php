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

// Support both GET and POST
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$lat = null;
$lon = null;
$radiusKm = 15.0;
$limit = 3;
$question = "What is near me?";

if ($method === 'POST') {
    $body = json_decode(file_get_contents('php://input'), true);
    if (is_array($body)) {
        if (isset($body['latitude'])) $lat = (float)$body['latitude'];
        elseif (isset($body['lat'])) $lat = (float)$body['lat'];

        if (isset($body['longitude'])) $lon = (float)$body['longitude'];
        elseif (isset($body['lon'])) $lon = (float)$body['lon'];

        if (isset($body['radius_km'])) $radiusKm = (float)$body['radius_km'];
        if (isset($body['limit'])) $limit = (int)$body['limit'];
        if (isset($body['question']) && trim((string)$body['question']) !== '') {
            $question = trim((string)$body['question']);
        }
    }
} else {
    if (isset($_GET['lat'])) $lat = (float)$_GET['lat'];
    elseif (isset($_GET['latitude'])) $lat = (float)$_GET['latitude'];

    if (isset($_GET['lon'])) $lon = (float)$_GET['lon'];
    elseif (isset($_GET['longitude'])) $lon = (float)$_GET['longitude'];

    if (isset($_GET['radius_km'])) $radiusKm = (float)$_GET['radius_km'];
    if (isset($_GET['limit'])) $limit = (int)$_GET['limit'];
    if (isset($_GET['q'])) $question = trim((string)$_GET['q']);
}

// 1. Validation
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

$radiusKm = max(0.5, min(100.0, $radiusKm));
$limit = max(1, min(10, $limit));

// 2. Fetch all active destinations with verified coordinates
$stmt = $conn->prepare("SELECT id, name, city, category, latitude, longitude, overview, timings, entry_fee FROM destinations WHERE is_active = 1 AND latitude IS NOT NULL AND longitude IS NOT NULL");
if (!$stmt) {
    http_response_code(500);
    echo json_encode(["status" => "error", "message" => "Database query preparation failed."], JSON_UNESCAPED_UNICODE);
    exit;
}

$stmt->execute();
$result = $stmt->get_result();

$nearbyList = [];
while ($row = $result->fetch_assoc()) {
    $destLat = (float)$row['latitude'];
    $destLon = (float)$row['longitude'];

    $dist = haversine_km($lat, $lon, $destLat, $destLon);

    if ($dist <= $radiusKm) {
        $roundedDist = round($dist, 1);
        $nearbyList[] = [
            "id" => (int)$row['id'],
            "name" => $row['name'],
            "city" => $row['city'],
            "category" => $row['category'],
            "latitude" => $destLat,
            "longitude" => $destLon,
            "distance_km" => $roundedDist,
            "overview" => $row['overview'],
            "timings" => $row['timings']
        ];
    }
}
$stmt->close();

// Sort by distance ascending
usort($nearbyList, fn($a, $b) => $a['distance_km'] <=> $b['distance_km']);

// Slice top N results
$topDestinations = array_slice($nearbyList, 0, $limit);
$foundCount = count($topDestinations);

// 3. No destinations found within radius (e.g. user is outside Chennai)
if ($foundCount === 0) {
    $unavailableMsg = "No Chennai tourist attractions were found near your current location (searched within " . round($radiusKm) . " km).";
    echo json_encode([
        "status" => "ok",
        "count" => 0,
        "user_coords" => ["latitude" => $lat, "longitude" => $lon],
        "radius_km" => $radiusKm,
        "destinations" => [],
        "topic" => "Nearby Attractions",
        "answer" => $unavailableMsg,
        "ai_generated" => false
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// 4. Construct compact verified facts with backend-calculated distances
$factLines = [];
$fallbackLines = [];
foreach ($topDestinations as $i => $d) {
    $num = $i + 1;
    $distStr = number_format($d['distance_km'], 1, '.', '');
    $factLines[] = "{$num}. {$d['name']} (about {$distStr} km from you). Category: {$d['category']}. Overview: {$d['overview']}";
    $fallbackLines[] = "{$d['name']} is about {$distStr} km away";
}

$verifiedFacts = "Nearest Verified Attractions:\n" . implode("\n", $factLines) .
    "\n\nINSTRUCTIONS: State the nearest attraction and its verified distance in km from the user.";
$deterministicAnswer = "The nearest attraction to you is " . $fallbackLines[0] . "." .
    (count($fallbackLines) > 1 ? " Also nearby: " . $fallbackLines[1] . "." : "");

// 5. Natural-language synthesis with local Gemma (if available)
$finalAnswer = $deterministicAnswer;
$isAi = false;

if (function_exists('call_local_gemma')) {
    $gemmaTopic = "Nearby Attractions";
    $aiAnswer = call_local_gemma($question, $gemmaTopic, $verifiedFacts, [
        'timeout' => 12
    ]);

    if ($aiAnswer !== null && trim($aiAnswer) !== '') {
        $finalAnswer = trim($aiAnswer);
        $isAi = true;
    }
}

// 6. Return standard JSON contract
echo json_encode([
    "status" => "ok",
    "count" => $foundCount,
    "user_coords" => ["latitude" => $lat, "longitude" => $lon],
    "radius_km" => $radiusKm,
    "destinations" => $topDestinations,
    "topic" => "Nearby Attractions",
    "answer" => $finalAnswer,
    "ai_generated" => $isAi
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
