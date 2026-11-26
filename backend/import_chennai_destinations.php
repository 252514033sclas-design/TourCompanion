<?php
/**
 * Import Script: Chennai Destinations 01-10
 * Updates verified fields for existing destinations 1-5 and imports destinations 6-10.
 * Populates destination_aliases with language detection (ta, hi, en).
 * 100% idempotent and safe to run multiple times.
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/config.php';
$conn->set_charset('utf8mb4');

function nullIfEmpty(?string $val): ?string {
    if ($val === null) return null;
    $trimmed = trim($val);
    if ($trimmed === '' || strtoupper($trimmed) === 'NULL') {
        return null;
    }
    return $trimmed;
}

function detectAliasLanguage(string $alias): string {
    if (preg_match('/[\x{0B80}-\x{0BFF}]/u', $alias)) {
        return 'ta';
    }
    if (preg_match('/[\x{0900}-\x{097F}]/u', $alias)) {
        return 'hi';
    }
    return 'en';
}

try {
    $report = [
        'status' => 'success',
        'destinations_before' => 0,
        'destinations_after' => 0,
        'existing_records_updated' => [],
        'new_records_inserted' => [],
        'aliases_inserted_count' => 0,
        'null_fields_retained' => [],
        'verification' => []
    ];

    // 1. Initial count
    $countRes = $conn->query("SELECT COUNT(*) AS total FROM destinations");
    $report['destinations_before'] = (int)$countRes->fetch_assoc()['total'];

    // 2. Locate CSV
    $csvPaths = [
        __DIR__ . '/../data/chennai_destinations_01_10_clean.csv',
        'C:/Users/SCAD LAB/AndroidStudioProjects/TourCompanion/backend/data/chennai_destinations_01_10_clean.csv',
        'C:/Users/SCAD LAB/Downloads/chennai_destinations_01_10_clean.csv'
    ];
    $csvFile = null;
    foreach ($csvPaths as $path) {
        if (file_exists($path)) {
            $csvFile = $path;
            break;
        }
    }
    if (!$csvFile) {
        throw new Exception("CSV file not found in search paths");
    }

    $handle = fopen($csvFile, 'r');
    if (!$handle) {
        throw new Exception("Unable to open CSV file: $csvFile");
    }

    $headers = fgetcsv($handle);
    if (!$headers) {
        throw new Exception("CSV file is empty or headers could not be read");
    }
    $headers = array_map(function($h) { return trim(trim($h, "\xEF\xBB\xBF")); }, $headers);

    $rows = [];
    while (($data = fgetcsv($handle)) !== false) {
        if (count($data) === count($headers)) {
            $rows[] = array_combine($headers, $data);
        }
    }
    fclose($handle);

    // Existing destinations 1-5 map (by canonical ID)
    // 1 -> Marina Beach
    // 2 -> Arulmigu Kapaleeshwarar Temple
    // 3 -> Fort St. George & St. Mary's Church (CSV: Fort St. George)
    // 4 -> Government Museum & National Art Gallery, Egmore
    // 5 -> Besant Nagar Beach (Elliot's Beach) (CSV: Besant Nagar / Elliot's Beach)
    $existingMap = [
        0 => 1, // Row index 0 -> ID 1
        1 => 2, // Row index 1 -> ID 2
        2 => 3, // Row index 2 -> ID 3
        3 => 4, // Row index 3 -> ID 4
        4 => 5  // Row index 4 -> ID 5
    ];

    $conn->begin_transaction();

    $updateStmt = $conn->prepare("UPDATE destinations SET 
        name_ta = ?, 
        name_hi = ?, 
        district = ?, 
        area = ?, 
        nearest_metro = ?, 
        nearest_bus_stop = ?, 
        visit_duration_min = ?, 
        source_url = ?, 
        verified_on = ? 
        WHERE id = ?");

    $insertStmt = $conn->prepare("INSERT INTO destinations (
        id, name, name_ta, name_hi, city, district, area, category, 
        latitude, longitude, search_tags, overview, timings, entry_fee, 
        how_to_reach, nearest_metro, nearest_bus_stop, best_time_to_visit, 
        visit_duration_min, things_to_do, safety_tips, nearby_attractions, 
        source_url, verified_on, is_active
    ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, 
        ?, ?, ?, ?, ?, ?, 
        ?, ?, ?, ?, 
        ?, ?, ?, ?, 
        ?, ?, 1
    ) ON DUPLICATE KEY UPDATE 
        name_ta = VALUES(name_ta),
        name_hi = VALUES(name_hi),
        district = VALUES(district),
        area = VALUES(area),
        category = VALUES(category),
        latitude = VALUES(latitude),
        longitude = VALUES(longitude),
        search_tags = VALUES(search_tags),
        overview = VALUES(overview),
        timings = VALUES(timings),
        entry_fee = VALUES(entry_fee),
        how_to_reach = VALUES(how_to_reach),
        nearest_metro = VALUES(nearest_metro),
        nearest_bus_stop = VALUES(nearest_bus_stop),
        best_time_to_visit = VALUES(best_time_to_visit),
        visit_duration_min = VALUES(visit_duration_min),
        things_to_do = VALUES(things_to_do),
        safety_tips = VALUES(safety_tips),
        nearby_attractions = VALUES(nearby_attractions),
        source_url = VALUES(source_url),
        verified_on = VALUES(verified_on)");

    $aliasStmt = $conn->prepare("INSERT INTO destination_aliases (destination_id, alias, language) 
        VALUES (?, ?, ?) 
        ON DUPLICATE KEY UPDATE language = VALUES(language)");

    $totalAliasesProcessed = 0;

    foreach ($rows as $index => $row) {
        $name = trim($row['name']);
        $name_ta = nullIfEmpty($row['name_ta']);
        $name_hi = nullIfEmpty($row['name_hi']);
        $district = nullIfEmpty($row['district']) ?? 'Chennai';
        $area = nullIfEmpty($row['area']);
        $city = nullIfEmpty($row['city']) ?? 'Chennai';
        $category = nullIfEmpty($row['category']) ?? 'Attraction';
        $overview = nullIfEmpty($row['overview']) ?? '';
        $timings = nullIfEmpty($row['timings']);
        $entry_fee = nullIfEmpty($row['entry_fee']);
        $how_to_reach = nullIfEmpty($row['how_to_reach']) ?? '';
        $best_time = nullIfEmpty($row['best_time']) ?? '';
        $visit_duration_min = nullIfEmpty($row['visit_duration_min']) !== null ? (int)$row['visit_duration_min'] : null;
        $things_to_do = nullIfEmpty($row['things_to_do']) ?? '';
        $safety_tips = nullIfEmpty($row['safety_tips']) ?? '';
        $nearby_attractions = nullIfEmpty($row['nearby_attractions']) ?? '';
        $latitude = nullIfEmpty($row['latitude']) !== null ? (float)$row['latitude'] : null;
        $longitude = nullIfEmpty($row['longitude']) !== null ? (float)$row['longitude'] : null;
        $nearest_metro = nullIfEmpty($row['nearest_metro']);
        $nearest_bus_stop = nullIfEmpty($row['nearest_bus_stop']);
        $source_url = nullIfEmpty($row['source_url']);
        $verified_on = nullIfEmpty($row['verified_on']);
        $aliasesRaw = trim($row['aliases'] ?? '');

        if (isset($existingMap[$index])) {
            // Destinations 1-5: UPDATE verified fields, preserving ID and existing verified coordinates & guide text
            $destId = $existingMap[$index];
            $updateStmt->bind_param(
                "ssssssissi",
                $name_ta,
                $name_hi,
                $district,
                $area,
                $nearest_metro,
                $nearest_bus_stop,
                $visit_duration_min,
                $source_url,
                $verified_on,
                $destId
            );
            if (!$updateStmt->execute()) {
                throw new Exception("Failed to update destination ID $destId: " . $updateStmt->error);
            }
            $report['existing_records_updated'][] = [
                'id' => $destId,
                'name' => $name,
                'area' => $area,
                'name_ta' => $name_ta,
                'name_hi' => $name_hi,
                'nearest_metro' => $nearest_metro,
                'nearest_bus_stop' => $nearest_bus_stop,
                'source_url' => $source_url,
                'verified_on' => $verified_on
            ];
        } else {
            // Destinations 6-10: INSERT with explicit ID (index + 1)
            $destId = $index + 1; // 6, 7, 8, 9, 10

            // Generate search_tags from aliases + name
            $searchTagsArray = array_filter(array_map('trim', explode('|', $aliasesRaw)));
            $searchTagsArray[] = $name;
            $searchTags = strtolower(implode(', ', array_unique($searchTagsArray)));

            $insertStmt->bind_param(
                "isssssssddssssssssssssss",
                $destId,
                $name,
                $name_ta,
                $name_hi,
                $city,
                $district,
                $area,
                $category,
                $latitude,
                $longitude,
                $searchTags,
                $overview,
                $timings,
                $entry_fee,
                $how_to_reach,
                $nearest_metro,
                $nearest_bus_stop,
                $best_time,
                $visit_duration_min,
                $things_to_do,
                $safety_tips,
                $nearby_attractions,
                $source_url,
                $verified_on
            );
            if (!$insertStmt->execute()) {
                throw new Exception("Failed to insert destination ID $destId ($name): " . $insertStmt->error);
            }
            $report['new_records_inserted'][] = [
                'id' => $destId,
                'name' => $name,
                'category' => $category,
                'area' => $area,
                'latitude' => $latitude,
                'longitude' => $longitude,
                'source_url' => $source_url,
                'verified_on' => $verified_on
            ];
        }

        // Process aliases into destination_aliases table
        if ($aliasesRaw !== '') {
            $aliases = explode('|', $aliasesRaw);
            foreach ($aliases as $alias) {
                $alias = trim($alias);
                if ($alias === '') continue;
                $lang = detectAliasLanguage($alias);
                $aliasStmt->bind_param("iss", $destId, $alias, $lang);
                if (!$aliasStmt->execute()) {
                    throw new Exception("Failed to insert alias '$alias' for destination ID $destId: " . $aliasStmt->error);
                }
                $totalAliasesProcessed++;
            }
        }
    }

    $conn->commit();
    $updateStmt->close();
    $insertStmt->close();
    $aliasStmt->close();

    // 3. Final Counts and Verifications
    $countAfter = $conn->query("SELECT COUNT(*) AS total FROM destinations");
    $report['destinations_after'] = (int)$countAfter->fetch_assoc()['total'];

    $chennaiCountRes = $conn->query("SELECT COUNT(*) AS total FROM destinations WHERE LOWER(city) = 'chennai'");
    $chennaiCount = (int)$chennaiCountRes->fetch_assoc()['total'];

    $report['aliases_inserted_count'] = $totalAliasesProcessed;

    // A-H Verification
    $destQuery = $conn->query("
        SELECT d.id, d.name, d.city, d.district, d.area, d.category, 
               d.latitude, d.longitude, d.timings, d.entry_fee, 
               d.source_url, d.verified_on, d.name_ta, d.name_hi, 
               d.nearest_metro, d.nearest_bus_stop, d.visit_duration_min,
               COUNT(a.id) AS alias_count
        FROM destinations d
        LEFT JOIN destination_aliases a ON d.id = a.destination_id
        GROUP BY d.id
        ORDER BY d.id ASC
    ");

    $destList = [];
    $nullFieldsFound = [];
    while ($row = $destQuery->fetch_assoc()) {
        $destList[] = [
            'id' => (int)$row['id'],
            'name' => $row['name'],
            'city' => $row['city'],
            'district' => $row['district'],
            'area' => $row['area'],
            'latitude' => $row['latitude'] !== null ? (float)$row['latitude'] : null,
            'longitude' => $row['longitude'] !== null ? (float)$row['longitude'] : null,
            'source_url' => $row['source_url'],
            'verified_on' => $row['verified_on'],
            'alias_count' => (int)$row['alias_count'],
            'has_tamil' => !empty($row['name_ta']),
            'has_hindi' => !empty($row['name_hi'])
        ];

        // Track NULL fields per destination
        foreach ($row as $col => $val) {
            if ($val === null) {
                $nullFieldsFound[$row['name']][] = $col;
            }
        }
    }

    // I. Confirm no duplicate destination names
    $dupNameRes = $conn->query("SELECT name, COUNT(*) AS c FROM destinations GROUP BY name HAVING c > 1");
    $duplicateNames = [];
    while ($r = $dupNameRes->fetch_assoc()) {
        $duplicateNames[] = $r['name'];
    }

    // J. Confirm no duplicate (alias, destination_id) records
    $dupAliasRes = $conn->query("SELECT alias, destination_id, COUNT(*) AS c FROM destination_aliases GROUP BY alias, destination_id HAVING c > 1");
    $duplicateAliases = [];
    while ($r = $dupAliasRes->fetch_assoc()) {
        $duplicateAliases[] = $r['alias'] . ' (dest: ' . $r['destination_id'] . ')';
    }

    // Language sample verification
    $langSampleRes = $conn->query("SELECT destination_id, alias, language FROM destination_aliases WHERE language IN ('ta', 'hi') LIMIT 10");
    $langSamples = [];
    while ($r = $langSampleRes->fetch_assoc()) {
        $langSamples[] = $r;
    }

    $report['verification'] = [
        'total_destinations' => $report['destinations_after'],
        'chennai_destinations' => $chennaiCount,
        'destinations_summary' => $destList,
        'duplicate_names_found' => $duplicateNames,
        'duplicate_aliases_found' => $duplicateAliases,
        'language_samples' => $langSamples
    ];
    $report['null_fields_retained'] = $nullFieldsFound;

    echo json_encode($report, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (Throwable $e) {
    if (isset($conn) && $conn instanceof mysqli) {
        @$conn->rollback();
    }
    http_response_code(500);
    echo json_encode([
        'status' => 'error',
        'message' => $e->getMessage()
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
}
