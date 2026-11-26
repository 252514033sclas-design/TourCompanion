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
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

try {
    $city = isset($_GET['city']) ? trim((string)$_GET['city']) : '';
    $category = isset($_GET['category']) ? trim((string)$_GET['category']) : '';
    $q = isset($_GET['q']) ? trim((string)$_GET['q']) : '';

    $baseConditions = ["is_active = 1"];
    $baseParams = [];
    $baseTypes = '';

    // City filter
    if ($city !== '') {
        $baseConditions[] = "LOWER(city) = LOWER(?)";
        $baseParams[] = $city;
        $baseTypes .= 's';
    }

    // Category filter
    if ($category !== '') {
        $baseConditions[] = "category LIKE ?";
        $baseParams[] = '%' . $category . '%';
        $baseTypes .= 's';
    }

    // Branch 1: General query without 'q' search
    if ($q === '') {
        $whereClause = implode(" AND ", $baseConditions);
        $sql = "SELECT id, name, city, category, latitude, longitude, search_tags, overview, timings, entry_fee,
                       how_to_reach, best_time_to_visit, things_to_do, safety_tips,
                       nearby_attractions, is_active
                FROM destinations
                WHERE {$whereClause}
                ORDER BY is_active DESC, name ASC";

        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            throw new Exception("Failed to prepare query");
        }
        if (!empty($baseParams)) {
            $stmt->bind_param($baseTypes, ...$baseParams);
        }
        if (!$stmt->execute()) {
            throw new Exception("Failed to execute query");
        }

        $result = $stmt->get_result();
        $destinations = formatDestinationRows($result);
        $stmt->close();

        sendSuccessResponse($destinations);
        exit;
    }

    // Branch 2: Primary destination search on name, category, tags, overview, things_to_do
    $pattern = '%' . $q . '%';
    $directConditions = $baseConditions;
    $directParams = $baseParams;
    $directTypes = $baseTypes;

    $directConditions[] = "(name LIKE ? OR category LIKE ? OR search_tags LIKE ? OR overview LIKE ? OR things_to_do LIKE ?)";
    for ($i = 0; $i < 5; $i++) {
        $directParams[] = $pattern;
        $directTypes .= 's';
    }

    $whereDirect = implode(" AND ", $directConditions);
    $sqlDirect = "SELECT id, name, city, category, latitude, longitude, search_tags, overview, timings, entry_fee,
                         how_to_reach, best_time_to_visit, things_to_do, safety_tips,
                         nearby_attractions, is_active
                  FROM destinations
                  WHERE {$whereDirect}
                  ORDER BY
                    CASE
                        WHEN LOWER(name) LIKE LOWER(?) THEN 1
                        WHEN LOWER(category) LIKE LOWER(?) THEN 2
                        WHEN LOWER(search_tags) LIKE LOWER(?) THEN 3
                        ELSE 4
                    END ASC,
                    name ASC";

    $orderParams = array_merge($directParams, [$pattern, $pattern, $pattern]);
    $orderTypes = $directTypes . 'sss';

    $stmt = $conn->prepare($sqlDirect);
    if (!$stmt) {
        throw new Exception("Failed to prepare search query");
    }
    $stmt->bind_param($orderTypes, ...$orderParams);
    if (!$stmt->execute()) {
        throw new Exception("Failed to execute search query");
    }

    $result = $stmt->get_result();
    $destinations = formatDestinationRows($result);
    $stmt->close();

    // If primary fields matched, return results
    if (!empty($destinations)) {
        sendSuccessResponse($destinations);
        exit;
    }

    // Branch 3: Fallback search on nearby_attractions if no direct matches
    $fallbackConditions = $baseConditions;
    $fallbackParams = $baseParams;
    $fallbackTypes = $baseTypes;

    $fallbackConditions[] = "nearby_attractions LIKE ?";
    $fallbackParams[] = $pattern;
    $fallbackTypes .= 's';

    $whereFallback = implode(" AND ", $fallbackConditions);
    $sqlFallback = "SELECT id, name, city, category, latitude, longitude, search_tags, overview, timings, entry_fee,
                           how_to_reach, best_time_to_visit, things_to_do, safety_tips,
                           nearby_attractions, is_active
                    FROM destinations
                    WHERE {$whereFallback}
                    ORDER BY is_active DESC, name ASC";

    $stmt = $conn->prepare($sqlFallback);
    if (!$stmt) {
        throw new Exception("Failed to prepare fallback query");
    }
    if (!empty($fallbackParams)) {
        $stmt->bind_param($fallbackTypes, ...$fallbackParams);
    }
    if (!$stmt->execute()) {
        throw new Exception("Failed to execute fallback query");
    }

    $result = $stmt->get_result();
    $destinations = formatDestinationRows($result);
    $stmt->close();

    sendSuccessResponse($destinations);

} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Unable to retrieve destinations."
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * Formats database rows into clean JSON-serializable associative arrays
 */
function formatDestinationRows(mysqli_result $result): array {
    $rows = [];
    while ($row = $result->fetch_assoc()) {
        $rows[] = [
            "id" => (int)$row['id'],
            "name" => $row['name'],
            "city" => $row['city'],
            "category" => $row['category'],
            "latitude" => $row['latitude'] !== null ? (float)$row['latitude'] : null,
            "longitude" => $row['longitude'] !== null ? (float)$row['longitude'] : null,
            "search_tags" => $row['search_tags'],
            "overview" => $row['overview'],
            "timings" => $row['timings'],
            "entry_fee" => $row['entry_fee'],
            "how_to_reach" => $row['how_to_reach'],
            "best_time_to_visit" => $row['best_time_to_visit'],
            "things_to_do" => $row['things_to_do'],
            "safety_tips" => $row['safety_tips'],
            "nearby_attractions" => $row['nearby_attractions'],
            "is_active" => (int)$row['is_active']
        ];
    }
    return $rows;
}

/**
 * Sends a standardized success JSON response
 */
function sendSuccessResponse(array $destinations): void {
    http_response_code(200);
    echo json_encode([
        "status" => "success",
        "count" => count($destinations),
        "destinations" => $destinations
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
}
