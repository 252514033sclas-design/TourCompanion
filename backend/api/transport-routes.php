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

/**
 * Parses the optional mode filter into a GTFS route_type integer, or exits 400 on invalid input.
 */
function parseRouteType(?string $modeParam): ?int {
    if ($modeParam === null || trim($modeParam) === '') {
        return null;
    }
    $modeLower = strtolower(trim($modeParam));
    if ($modeLower === 'bus' || $modeLower === '3') {
        return 3;
    }
    if ($modeLower === 'metro' || $modeLower === 'subway' || $modeLower === '1') {
        return 1;
    }
    if ($modeLower === 'rail' || $modeLower === 'train' || $modeLower === 'suburban' || $modeLower === '2') {
        return 2;
    }
    if ($modeLower === 'tram' || $modeLower === '0') {
        return 0;
    }
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Invalid 'mode' filter. Supported values: bus, metro, rail"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/**
 * Appends agency filter SQL clause and parameters when $agencyParam is non-empty.
 */
function appendAgencyFilter(string $agencyParam, array &$whereClauses, array &$params, string &$types): void {
    if ($agencyParam === '') {
        return;
    }
    $agencyUpper = strtoupper($agencyParam);
    if ($agencyUpper === 'CMRL') {
        $whereClauses[] = "(r.agency_id = 'CMRL' OR r.route_id LIKE 'CMRL-%')";
    } elseif ($agencyUpper === '69' || $agencyUpper === 'MTC') {
        $whereClauses[] = "(r.agency_id = '69' OR r.route_id LIKE 'MTC-%')";
    } elseif ($agencyUpper === 'SR' || $agencyUpper === 'SRR') {
        $whereClauses[] = "(r.agency_id = 'SR' OR r.route_id LIKE 'SR-%' OR r.route_id LIKE 'SRR-%')";
    } else {
        $whereClauses[] = "r.agency_id = ?";
        $params[] = $agencyParam;
        $types .= 's';
    }
}

/**
 * Resolves a station or bus stop name via transport_stop_aliases -> transport_stops,
 * expanding parent stations to their child platform stops.
 */
function resolveStopIds(mysqli $conn, string $name): array {
    $lower = strtolower(trim($name));
    if ($lower === '') {
        return [];
    }

    $ids = [];

    // 1. Exact alias match in transport_stop_aliases
    $stmt = $conn->prepare("SELECT stop_id FROM transport_stop_aliases WHERE LOWER(alias) = ?");
    if ($stmt) {
        $stmt->bind_param("s", $lower);
        $stmt->execute();
        $res = $stmt->get_result();
        while ($row = $res->fetch_row()) {
            $ids[(string)$row[0]] = true;
        }
        $stmt->close();
    }

    // 2. Exact stop_name match in transport_stops
    if (empty($ids)) {
        $stmt = $conn->prepare("SELECT stop_id FROM transport_stops WHERE LOWER(stop_name) = ?");
        if ($stmt) {
            $stmt->bind_param("s", $lower);
            $stmt->execute();
            $res = $stmt->get_result();
            while ($row = $res->fetch_row()) {
                $ids[(string)$row[0]] = true;
            }
            $stmt->close();
        }
    }

    // 3. Partial LIKE match on stop_name if still unresolved
    if (empty($ids)) {
        $pattern = '%' . $lower . '%';
        $stmt = $conn->prepare("SELECT stop_id FROM transport_stops WHERE LOWER(stop_name) LIKE ? LIMIT 25");
        if ($stmt) {
            $stmt->bind_param("s", $pattern);
            $stmt->execute();
            $res = $stmt->get_result();
            while ($row = $res->fetch_row()) {
                $ids[(string)$row[0]] = true;
            }
            $stmt->close();
        }
    }

    // 4. Expand any parent stations to their platform-level child stops
    if (!empty($ids)) {
        $seed = array_keys($ids);
        $placeholders = implode(',', array_fill(0, count($seed), '?'));
        $stmt = $conn->prepare("SELECT stop_id FROM transport_stops WHERE parent_station IN ($placeholders)");
        if ($stmt) {
            $stmt->bind_param(str_repeat('s', count($seed)), ...$seed);
            $stmt->execute();
            $res = $stmt->get_result();
            while ($row = $res->fetch_row()) {
                $ids[(string)$row[0]] = true;
            }
            $stmt->close();
        }
    }

    return array_keys($ids);
}

/**
 * Formats mysqli_result rows into the standard transport-routes JSON item array.
 */
function formatRouteRows(mysqli_result $result): array {
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
    return $data;
}

try {
    $stopId = isset($_GET['stop_id']) ? trim((string)$_GET['stop_id']) : '';
    $origin = isset($_GET['origin']) ? trim((string)$_GET['origin']) : '';
    $destination = isset($_GET['destination']) ? trim((string)$_GET['destination']) : '';
    $q = isset($_GET['q']) ? trim((string)$_GET['q']) : '';
    $agencyParam = isset($_GET['agency']) ? trim((string)$_GET['agency']) : '';
    $modeParam = isset($_GET['mode']) ? trim((string)$_GET['mode']) : '';

    $routeType = parseRouteType($modeParam);

    // Branch A: Origin -> Destination deterministic route search via transport_route_stops
    if ($origin !== '' && $destination !== '') {
        $origStopIds = resolveStopIds($conn, $origin);
        $destStopIds = resolveStopIds($conn, $destination);

        if (empty($origStopIds) || empty($destStopIds)) {
            echo json_encode([
                "status" => "success",
                "count" => 0,
                "message" => "I couldn't find a verified direct route between those locations in my transport data.",
                "data" => []
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        $phOrig = implode(',', array_fill(0, count($origStopIds), '?'));
        $phDest = implode(',', array_fill(0, count($destStopIds), '?'));

        $whereClauses = [
            "orig.stop_id IN ($phOrig)",
            "dest.stop_id IN ($phDest)"
        ];
        $params = array_merge($origStopIds, $destStopIds);
        $types = str_repeat('s', count($origStopIds)) . str_repeat('s', count($destStopIds));

        if ($routeType !== null) {
            $whereClauses[] = "r.route_type = ?";
            $params[] = $routeType;
            $types .= 'i';
        }
        appendAgencyFilter($agencyParam, $whereClauses, $params, $types);

        $whereSql = implode(' AND ', $whereClauses);
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
                WHERE {$whereSql}
                GROUP BY r.route_id, r.agency_id, agency_name, r.route_short_name, r.route_long_name,
                         r.route_type, r.route_desc, r.route_color, r.route_text_color
                ORDER BY stop_hops ASC, r.route_short_name ASC
                LIMIT 50";

        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            throw new Exception("Failed to prepare origin-destination routes statement");
        }
        $stmt->bind_param($types, ...$params);
        $stmt->execute();
        $data = formatRouteRows($stmt->get_result());
        $stmt->close();

        $response = [
            "status" => "success",
            "count" => count($data),
            "data" => $data
        ];
        if (empty($data)) {
            $response["message"] = "I couldn't find a verified direct route between those locations in my transport data.";
        }
        echo json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    // Branch B: Single-stop lookup by stop_id OR single origin/destination stop name
    $singleStopName = $origin !== '' ? $origin : $destination;
    if ($stopId !== '' || ($singleStopName !== '' && $q === '')) {
        $resolvedStopIds = [];
        $stopRow = null;

        if ($stopId !== '') {
            $stmtCheck = $conn->prepare("SELECT stop_id, stop_name FROM transport_stops WHERE stop_id = ? LIMIT 1");
            if (!$stmtCheck) {
                throw new Exception("Failed to prepare stop verification statement");
            }
            $stmtCheck->bind_param("s", $stopId);
            $stmtCheck->execute();
            $stopRow = $stmtCheck->get_result()->fetch_assoc();
            $stmtCheck->close();

            if (!$stopRow) {
                http_response_code(404);
                echo json_encode([
                    "status" => "error",
                    "message" => "Stop '{$stopId}' not found"
                ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                exit;
            }
            $resolvedStopIds = [$stopId];
            $stmtChild = $conn->prepare("SELECT stop_id FROM transport_stops WHERE parent_station = ?");
            if ($stmtChild) {
                $stmtChild->bind_param("s", $stopId);
                $stmtChild->execute();
                $resChild = $stmtChild->get_result();
                while ($c = $resChild->fetch_row()) {
                    $resolvedStopIds[] = (string)$c[0];
                }
                $stmtChild->close();
            }
        } else {
            $resolvedStopIds = resolveStopIds($conn, $singleStopName);
        }

        if (empty($resolvedStopIds)) {
            echo json_encode([
                "status" => "success",
                "count" => 0,
                "message" => "I couldn't find a verified direct route for that stop in my transport data.",
                "data" => []
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        $phStops = implode(',', array_fill(0, count($resolvedStopIds), '?'));
        $whereClauses = ["trs.stop_id IN ($phStops)"];
        $params = $resolvedStopIds;
        $types = str_repeat('s', count($resolvedStopIds));

        if ($routeType !== null) {
            $whereClauses[] = "r.route_type = ?";
            $params[] = $routeType;
            $types .= 'i';
        }
        appendAgencyFilter($agencyParam, $whereClauses, $params, $types);

        $whereSql = implode(' AND ', $whereClauses);
        $sql = "SELECT DISTINCT r.route_id, r.agency_id,
                       COALESCE(a.agency_name, CASE WHEN r.route_id LIKE 'CMRL-%' THEN 'Chennai Metro Rail Limited' ELSE NULL END) AS agency_name,
                       r.route_short_name, r.route_long_name, r.route_type,
                       r.route_desc, r.route_color, r.route_text_color
                FROM transport_route_stops trs
                JOIN transport_routes r ON r.route_id = trs.route_id
                LEFT JOIN transport_agencies a ON r.agency_id = a.agency_id
                WHERE {$whereSql}
                ORDER BY r.route_short_name ASC, r.route_long_name ASC
                LIMIT 50";

        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            throw new Exception("Failed to prepare single-stop routes statement");
        }
        $stmt->bind_param($types, ...$params);
        $stmt->execute();
        $data = formatRouteRows($stmt->get_result());
        $stmt->close();

        $response = [
            "status" => "success",
            "count" => count($data),
            "data" => $data
        ];
        if ($stopRow !== null) {
            $response["stop_id"] = $stopRow['stop_id'];
            $response["stop_name"] = $stopRow['stop_name'];
        }
        if (empty($data)) {
            $response["message"] = "I couldn't find a verified direct route for that location in my transport data.";
        }
        echo json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    // Branch C: Existing textual / mode / agency route search
    $whereClauses = [];
    $params = [];
    $types = '';

    if ($routeType !== null) {
        $whereClauses[] = "r.route_type = ?";
        $params[] = $routeType;
        $types .= 'i';
    }
    appendAgencyFilter($agencyParam, $whereClauses, $params, $types);

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

    $data = formatRouteRows($stmt->get_result());
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
