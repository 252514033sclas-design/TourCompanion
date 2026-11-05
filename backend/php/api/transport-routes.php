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

try {
    $stopId = isset($_GET['stop_id']) ? trim((string)$_GET['stop_id']) : '';
    $q = isset($_GET['q']) ? trim((string)$_GET['q']) : '';
    $agencyParam = isset($_GET['agency']) ? trim((string)$_GET['agency']) : '';
    $modeParam = isset($_GET['mode']) ? trim((string)$_GET['mode']) : '';

    // Branch B: Search routes serving a stop
    if ($stopId !== '') {
        // Validate stop existence in transport_stops
        $stmtCheck = $conn->prepare("SELECT stop_id, stop_name FROM transport_stops WHERE stop_id = ? LIMIT 1");
        if (!$stmtCheck) {
            throw new Exception("Failed to prepare stop verification statement");
        }
        $stmtCheck->bind_param("s", $stopId);
        $stmtCheck->execute();
        $resCheck = $stmtCheck->get_result();
        $stopRow = $resCheck->fetch_assoc();
        $stmtCheck->close();

        if (!$stopRow) {
            http_response_code(404);
            echo json_encode([
                "status" => "error",
                "message" => "Stop '{$stopId}' not found"
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        // As documented in Phase 1 & 2 requirements:
        // The static MVP schema does not import stop_times.txt or an explicit route-stop mapping table.
        // Rather than guessing or inventing route-stop relationships, return a clear notice.
        echo json_encode([
            "status" => "success",
            "count" => 0,
            "stop_id" => $stopRow['stop_id'],
            "stop_name" => $stopRow['stop_name'],
            "message" => "Route-to-stop mapping is not available in the current static dataset without stop_times data.",
            "data" => []
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    $whereClauses = [];
    $params = [];
    $types = '';

    // Mode filter mapping to GTFS route_type
    if ($modeParam !== '') {
        $modeLower = strtolower($modeParam);
        $routeType = null;
        if ($modeLower === 'bus' || $modeLower === '3') {
            $routeType = 3;
        } elseif ($modeLower === 'metro' || $modeLower === 'subway' || $modeLower === '1') {
            $routeType = 1;
        } elseif ($modeLower === 'rail' || $modeLower === 'train' || $modeLower === 'suburban' || $modeLower === '2') {
            $routeType = 2;
        } elseif ($modeLower === 'tram' || $modeLower === '0') {
            $routeType = 0;
        } else {
            http_response_code(400);
            echo json_encode([
                "status" => "error",
                "message" => "Invalid 'mode' filter. Supported values: bus, metro, rail"
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        $whereClauses[] = "r.route_type = ?";
        $params[] = $routeType;
        $types .= 'i';
    }

    // Agency filter
    if ($agencyParam !== '') {
        $agencyUpper = strtoupper($agencyParam);
        if ($agencyUpper === 'CMRL') {
            // Includes both routes referencing agency CMRL and the 28 unassigned routes prefixed with CMRL-
            $whereClauses[] = "(r.agency_id = 'CMRL' OR r.route_id LIKE 'CMRL-%')";
        } elseif ($agencyUpper === '69' || $agencyUpper === 'MTC') {
            $whereClauses[] = "(r.agency_id = '69' OR r.route_id LIKE 'MTC-%')";
        } elseif ($agencyUpper === 'SR' || $agencyUpper === 'SRR') {
            $whereClauses[] = "(r.agency_id = 'SR' OR r.route_id LIKE 'SR-%')";
        } else {
            $whereClauses[] = "r.agency_id = ?";
            $params[] = $agencyParam;
            $types .= 's';
        }
    }

    // Search query matching short name, long name, or recognized line colors
    if ($q !== '') {
        $qTrim = trim($q);
        $qLower = strtolower($qTrim);

        if ($qLower === 'blue' || $qLower === 'blue line') {
            $whereClauses[] = "(r.route_color = '000092' OR r.route_short_name LIKE ? OR r.route_long_name LIKE ?)";
            $pattern = '%' . $qTrim . '%';
            $params[] = $pattern;
            $params[] = $pattern;
            $types .= 'ss';
        } elseif ($qLower === 'green' || $qLower === 'green line') {
            $whereClauses[] = "(r.route_color = '00A700' OR r.route_short_name LIKE ? OR r.route_long_name LIKE ?)";
            $pattern = '%' . $qTrim . '%';
            $params[] = $pattern;
            $params[] = $pattern;
            $types .= 'ss';
        } else {
            $whereClauses[] = "(r.route_short_name = ? OR r.route_short_name LIKE ? OR r.route_long_name LIKE ?)";
            $exact = $qTrim;
            $like = '%' . $qTrim . '%';
            $params[] = $exact;
            $params[] = $like;
            $params[] = $like;
            $types .= 'sss';
        }
    }

    $whereSql = !empty($whereClauses) ? "WHERE " . implode(" AND ", $whereClauses) : "";

    $sql = "SELECT r.route_id, r.agency_id,
                   COALESCE(a.agency_name, CASE WHEN r.route_id LIKE 'CMRL-%' THEN 'Chennai Metro Rail Limited' ELSE NULL END) AS agency_name,
                   r.route_short_name, r.route_long_name, r.route_type,
                   r.route_desc, r.route_color, r.route_text_color
            FROM transport_routes r
            LEFT JOIN transport_agencies a ON r.agency_id = a.agency_id
            {$whereSql}
            ORDER BY 
              CASE WHEN ? != '' AND LOWER(r.route_short_name) = LOWER(?) THEN 1 ELSE 2 END ASC,
              r.route_short_name ASC,
              r.route_long_name ASC
            LIMIT 50";

    $orderQ = $q;
    $params[] = $orderQ;
    $params[] = $orderQ;
    $types .= 'ss';

    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        throw new Exception("Failed to prepare routes statement");
    }

    $stmt->bind_param($types, ...$params);
    if (!$stmt->execute()) {
        throw new Exception("Failed to execute routes statement");
    }

    $result = $stmt->get_result();
    $data = [];

    while ($row = $result->fetch_assoc()) {
        $agencyId = $row['agency_id'] !== null ? (string)$row['agency_id'] : null;

        $data[] = [
            'route_id' => (string)$row['route_id'],
            'agency_id' => $agencyId,
            'agency_name' => $row['agency_name'] !== null ? (string)$row['agency_name'] : null,
            'route_short_name' => (string)$row['route_short_name'],
            'route_long_name' => (string)$row['route_long_name'],
            'route_type' => (int)$row['route_type'],
            'route_desc' => $row['route_desc'] !== null && $row['route_desc'] !== '' ? (string)$row['route_desc'] : null,
            'route_color' => $row['route_color'] !== null && $row['route_color'] !== '' ? (string)$row['route_color'] : null,
            'route_text_color' => $row['route_text_color'] !== null && $row['route_text_color'] !== '' ? (string)$row['route_text_color'] : null
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
        "message" => "An internal error occurred while retrieving routes"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}
