<?php
/**
 * Idempotent Destinations Coordinates Migration Script
 * Adds latitude and longitude columns (DECIMAL(10,7) NULL) to `destinations` table
 * and updates the five verified Chennai destinations.
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/config.php';

try {
    // 1. Check if latitude column exists
    $checkLat = $conn->query("SHOW COLUMNS FROM destinations LIKE 'latitude'");
    $hasLat = ($checkLat && $checkLat->num_rows > 0);

    // Check if longitude column exists
    $checkLon = $conn->query("SHOW COLUMNS FROM destinations LIKE 'longitude'");
    $hasLon = ($checkLon && $checkLon->num_rows > 0);

    if (!$hasLat || !$hasLon) {
        $alterSql = "ALTER TABLE destinations ";
        $clauses = [];
        if (!$hasLat) {
            $clauses[] = "ADD COLUMN latitude DECIMAL(10,7) NULL AFTER category";
        }
        if (!$hasLon) {
            $clauses[] = "ADD COLUMN longitude DECIMAL(10,7) NULL AFTER " . ($hasLat ? "latitude" : "category");
        }
        $alterSql .= implode(", ", $clauses);

        if (!$conn->query($alterSql)) {
            throw new Exception("Failed to add columns: " . $conn->error);
        }
    }

    // 2. Verified representative coordinates for the 5 Chennai destinations
    $coordinates = [
        1 => ['lat' => 13.054180, 'lon' => 80.283680, 'name' => 'Marina Beach'],
        2 => ['lat' => 13.033700, 'lon' => 80.269900, 'name' => 'Arulmigu Kapaleeshwarar Temple'],
        3 => ['lat' => 13.079722, 'lon' => 80.286944, 'name' => "Fort St. George & St. Mary's Church"],
        4 => ['lat' => 13.071100, 'lon' => 80.256900, 'name' => 'Government Museum & National Art Gallery, Egmore'],
        5 => ['lat' => 12.999529, 'lon' => 80.272411, 'name' => "Besant Nagar Beach (Elliot's Beach)"]
    ];

    $updateStmt = $conn->prepare("UPDATE destinations SET latitude = ?, longitude = ? WHERE id = ?");
    if (!$updateStmt) {
        throw new Exception("Failed to prepare update statement: " . $conn->error);
    }

    $updatedRecords = [];
    foreach ($coordinates as $id => $data) {
        $lat = $data['lat'];
        $lon = $data['lon'];
        $updateStmt->bind_param("ddi", $lat, $lon, $id);
        if (!$updateStmt->execute()) {
            throw new Exception("Failed to update destination ID $id: " . $updateStmt->error);
        }
        $updatedRecords[] = [
            'id' => $id,
            'name' => $data['name'],
            'latitude' => $lat,
            'longitude' => $lon
        ];
    }
    $updateStmt->close();

    // 3. Query current state to verify
    $verifyRes = $conn->query("SELECT id, name, latitude, longitude FROM destinations ORDER BY id ASC");
    $currentRows = [];
    while ($row = $verifyRes->fetch_assoc()) {
        $currentRows[] = [
            'id' => (int)$row['id'],
            'name' => $row['name'],
            'latitude' => $row['latitude'] !== null ? (float)$row['latitude'] : null,
            'longitude' => $row['longitude'] !== null ? (float)$row['longitude'] : null
        ];
    }

    echo json_encode([
        'status' => 'success',
        'message' => 'Destinations coordinates migrated successfully',
        'updated_count' => count($updatedRecords),
        'destinations' => $currentRows
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode([
        'status' => 'error',
        'message' => $e->getMessage()
    ], JSON_UNESCAPED_UNICODE);
}
