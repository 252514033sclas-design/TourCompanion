<?php
/**
 * Idempotent Destinations Schema Extension & Aliases Migration Script
 * Phase 2: Adds missing schema fields to `destinations` table for Chennai/Tamil Nadu expansion.
 * Phase 3: Creates `destination_aliases` table with foreign key to `destinations(id)`.
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/config.php';

try {
    $report = [
        'status' => 'success',
        'phase1_inspection' => [],
        'phase2_columns_added' => [],
        'phase2_columns_existing' => [],
        'phase3_aliases_table' => '',
        'verification' => []
    ];

    // 1. Inspect existing columns in destinations
    $existingColsRes = $conn->query("SHOW COLUMNS FROM destinations");
    if (!$existingColsRes) {
        throw new Exception("Failed to inspect destinations table: " . $conn->error);
    }

    $existingColumns = [];
    while ($col = $existingColsRes->fetch_assoc()) {
        $existingColumns[$col['Field']] = [
            'type' => $col['Type'],
            'null' => $col['Null'],
            'default' => $col['Default']
        ];
    }
    $report['phase1_inspection']['initial_column_count'] = count($existingColumns);
    $report['phase1_inspection']['existing_columns'] = array_keys($existingColumns);

    // 2. Desired fields definition
    $desiredFields = [
        'name_ta' => "VARCHAR(150) NULL AFTER name",
        'name_hi' => "VARCHAR(150) NULL AFTER name_ta",
        'district' => "VARCHAR(80) NOT NULL DEFAULT 'Chennai' AFTER city",
        'area' => "VARCHAR(100) NULL AFTER district",
        'visit_duration_min' => "SMALLINT UNSIGNED NULL AFTER best_time_to_visit",
        'nearest_metro' => "VARCHAR(120) NULL AFTER how_to_reach",
        'nearest_bus_stop' => "VARCHAR(120) NULL AFTER nearest_metro",
        'source_url' => "VARCHAR(500) NULL AFTER nearby_attractions",
        'verified_on' => "DATE NULL AFTER source_url"
    ];

    // Add each missing column idempotently
    foreach ($desiredFields as $colName => $colDef) {
        if (!isset($existingColumns[$colName])) {
            $alterSql = "ALTER TABLE destinations ADD COLUMN `{$colName}` {$colDef}";
            if (!$conn->query($alterSql)) {
                throw new Exception("Failed to add column {$colName}: " . $conn->error);
            }
            $report['phase2_columns_added'][] = $colName;
            // Update local tracker so subsequent 'AFTER' references work
            $existingColumns[$colName] = true;
        } else {
            $report['phase2_columns_existing'][] = $colName;
        }
    }

    // 3. Inspect or create destination_aliases table
    $tableCheck = $conn->query("SHOW TABLES LIKE 'destination_aliases'");
    if ($tableCheck && $tableCheck->num_rows > 0) {
        $report['phase3_aliases_table'] = 'already_exists';
    } else {
        // Note: destinations.id is INT(10) UNSIGNED, so destination_id must be INT UNSIGNED for foreign key compatibility
        $createAliasesSql = "CREATE TABLE destination_aliases (
            id INT AUTO_INCREMENT PRIMARY KEY,
            destination_id INT UNSIGNED NOT NULL,
            alias VARCHAR(150) NOT NULL,
            language VARCHAR(10) NOT NULL,
            FOREIGN KEY (destination_id)
                REFERENCES destinations(id)
                ON DELETE CASCADE,
            UNIQUE KEY uq_alias (alias, destination_id),
            INDEX idx_alias (alias)
        ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci ENGINE=InnoDB";

        if (!$conn->query($createAliasesSql)) {
            throw new Exception("Failed to create destination_aliases table: " . $conn->error);
        }
        $report['phase3_aliases_table'] = 'created_successfully';
    }

    // 4. Verification queries
    // Verify row count
    $destCountRes = $conn->query("SELECT COUNT(*) AS total FROM destinations")->fetch_assoc();
    $report['verification']['destination_count'] = (int)$destCountRes['total'];

    // Verify 5 existing destinations have district='Chennai' and existing coordinates intact
    $verifyDestsRes = $conn->query("SELECT id, name, city, district, latitude, longitude, area, name_ta, name_hi, verified_on, source_url FROM destinations ORDER BY id ASC");
    $destRecords = [];
    while ($row = $verifyDestsRes->fetch_assoc()) {
        $destRecords[] = [
            'id' => (int)$row['id'],
            'name' => $row['name'],
            'city' => $row['city'],
            'district' => $row['district'],
            'latitude' => $row['latitude'] !== null ? (float)$row['latitude'] : null,
            'longitude' => $row['longitude'] !== null ? (float)$row['longitude'] : null,
            'area' => $row['area'],
            'name_ta' => $row['name_ta'],
            'name_hi' => $row['name_hi'],
            'verified_on' => $row['verified_on'],
            'source_url' => $row['source_url']
        ];
    }
    $report['verification']['destinations'] = $destRecords;

    // Verify destination_aliases structure
    $aliasesColsRes = $conn->query("SHOW COLUMNS FROM destination_aliases");
    $aliasesCols = [];
    while ($col = $aliasesColsRes->fetch_assoc()) {
        $aliasesCols[] = [
            'field' => $col['Field'],
            'type' => $col['Type'],
            'null' => $col['Null'],
            'key' => $col['Key']
        ];
    }
    $report['verification']['destination_aliases_columns'] = $aliasesCols;

    echo json_encode($report, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode([
        'status' => 'error',
        'message' => $e->getMessage()
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
}
