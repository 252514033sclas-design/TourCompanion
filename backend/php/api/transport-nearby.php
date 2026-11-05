<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

// Method validation: only GET requests are allowed
if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode([
        "status" => "error",
        "message" => "Only GET requests are allowed"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// Coordinate validation: lat and lon are required
if (!isset($_GET['lat']) || !isset($_GET['lon'])) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Parameters 'lat' and 'lon' are required"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

$rawLat = trim((string)$_GET['lat']);
$rawLon = trim((string)$_GET['lon']);

if ($rawLat === '' || $rawLon === '' || !is_numeric($rawLat) || !is_numeric($rawLon)) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "'lat' and 'lon' must be numeric coordinates"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

$lat = (float)$rawLat;
$lon = (float)$rawLon;

if ($lat < -90.0 || $lat > 90.0) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "'lat' must be between -90 and 90"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if ($lon < -180.0 || $lon > 180.0) {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "'lon' must be between -180 and 180"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

// Radius validation
$radiusKm = 2.0;
if (isset($_GET['radius_km'])) {
    $rawRadius = trim((string)$_GET['radius_km']);
    if (!is_numeric($rawRadius)) {
        http_response_code(400);
        echo json_encode([
            "status" => "error",
            "message" => "'radius_km' must be a numeric value"
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }
    $radiusKm = (float)$rawRadius;
    if ($radiusKm <= 0.0) {
        http_response_code(400);
        echo json_encode([
            "status" => "error",
            "message" => "'radius_km' must be greater than 0"
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }
    if ($radiusKm > 25.0) {
        http_response_code(400);
        echo json_encode([
            "status" => "error",
            "message" => "'radius_km' cannot exceed 25 km"
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }
}

// Optional Agency / Mode filter
$agencyParam = isset($_GET['agency']) ? trim((string)$_GET['agency']) : '';
$modeParam = isset($_GET['mode']) ? trim((string)$_GET['mode']) : '';

try {
    // 1 deg latitude ~= 111.045 km
    $latDelta = $radiusKm / 111.045;
    $cosLat = cos(deg2rad($lat));
    $lonDelta = $radiusKm / (111.045 * max(abs($cosLat), 0.001));

    $minLat = $lat - $latDelta;
    $maxLat = $lat + $latDelta;
    $minLon = $lon - $lonDelta;
    $maxLon = $lon + $lonDelta;

    $whereClauses = [
        "stop_lat BETWEEN ? AND ?",
        "stop_lon BETWEEN ? AND ?"
    ];
    $params = [$lat, $lon, $lat, $minLat, $maxLat, $minLon, $maxLon];
    $types = "ddddddd";

    // Agency filter mapped to stop_id prefixes
    if ($agencyParam !== '') {
        $agencyUpper = strtoupper($agencyParam);
        if ($agencyUpper === '69' || $agencyUpper === 'MTC') {
            $whereClauses[] = "stop_id LIKE 'MTC-%'";
        } elseif ($agencyUpper === 'CMRL') {
            $whereClauses[] = "stop_id LIKE 'CMRL-%'";
        } elseif ($agencyUpper === 'SR' || $agencyUpper === 'SRR') {
            $whereClauses[] = "(stop_id LIKE 'SR-%' OR stop_id LIKE 'SRR-%')";
        } else {
            // Unrecognized agency -> return empty results cleanly
            echo json_encode([
                "status" => "success",
                "count" => 0,
                "data" => []
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }
    }

    // Mode filter mapped to stop types
    if ($modeParam !== '') {
        $modeLower = strtolower($modeParam);
        if ($modeLower === 'bus' || $modeLower === '3') {
            $whereClauses[] = "stop_id LIKE 'MTC-%'";
        } elseif ($modeLower === 'metro' || $modeLower === 'subway' || $modeLower === '1') {
            $whereClauses[] = "stop_id LIKE 'CMRL-%'";
        } elseif ($modeLower === 'rail' || $modeLower === 'train' || $modeLower === 'suburban' || $modeLower === '2') {
            $whereClauses[] = "(stop_id LIKE 'SR-%' OR stop_id LIKE 'SRR-%')";
        }
    }

    $whereSql = implode(" AND ", $whereClauses);

    // Haversine formula calculation
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
    $types .= "d";

    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        throw new Exception("Failed to prepare nearby stops statement");
    }

    $stmt->bind_param($types, ...$params);
    if (!$stmt->execute()) {
        throw new Exception("Failed to execute nearby stops statement");
    }

    $result = $stmt->get_result();
    $data = [];

    while ($row = $result->fetch_assoc()) {
        $stopId = (string)$row['stop_id'];
        $agencyInfo = null;

        if (strpos($stopId, 'MTC-') === 0) {
            $agencyInfo = [
                'agency_id' => '69',
                'agency_name' => 'MTC',
                'agency_full_name' => 'Metropolitan Transport Corporation'
            ];
        } elseif (strpos($stopId, 'CMRL-') === 0) {
            $agencyInfo = [
                'agency_id' => 'CMRL',
                'agency_name' => 'CMRL',
                'agency_full_name' => 'Chennai Metro Rail Limited'
            ];
        } elseif (strpos($stopId, 'SR-') === 0 || strpos($stopId, 'SRR-') === 0) {
            $agencyInfo = [
                'agency_id' => 'SR',
                'agency_name' => 'Southern Railway',
                'agency_full_name' => 'Southern Railway'
            ];
        }

        $data[] = [
            'stop_id' => $stopId,
            'stop_name' => (string)$row['stop_name'],
            'stop_lat' => (float)$row['stop_lat'],
            'stop_lon' => (float)$row['stop_lon'],
            'location_type' => (int)$row['location_type'],
            'parent_station' => $row['parent_station'] !== null && $row['parent_station'] !== '' ? (string)$row['parent_station'] : null,
            'distance_km' => (float)$row['distance_km'],
            'agency' => $agencyInfo
        ];
    }

    $stmt->close();

    echo json_encode([
        "status" => "success",
        "count" => count($data),
        "data" => $data
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (Exception $e) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "An internal error occurred while retrieving nearby stops"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

