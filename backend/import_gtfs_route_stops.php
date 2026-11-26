<?php
declare(strict_types=1);

/**
 * GTFS Route-to-Stop Sequence & Transport Stop Alias Importer
 *
 * Creates and populates:
 * 1. `transport_route_stops` from `trips.txt` + `stop_times.txt`
 * 2. `transport_stop_aliases` for safe natural-language stop resolution
 */

if (php_sapi_name() !== 'cli') {
    die("This script can only be run from the command line.\n");
}

ini_set('memory_limit', '1024M');
require_once __DIR__ . '/config.php';

$gtfsDir = 'C:/Users/SCAD LAB/gtfs_work/extracted/';
if (!is_dir($gtfsDir)) {
    die("GTFS extracted directory not found at: $gtfsDir\n");
}

echo "Starting GTFS Route-Stops & Alias Migration...\n";
echo "Database: $dbname\n\n";

// 1. Create transport_route_stops
$sqlRouteStops = "CREATE TABLE IF NOT EXISTS `transport_route_stops` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `route_id` VARCHAR(50) NOT NULL,
    `stop_id` VARCHAR(50) NOT NULL,
    `direction_id` TINYINT NOT NULL DEFAULT 0,
    `stop_sequence` INT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_trs_route_dir_stop_seq` (`route_id`, `direction_id`, `stop_id`, `stop_sequence`),
    KEY `idx_trs_route` (`route_id`),
    KEY `idx_trs_stop` (`stop_id`),
    KEY `idx_trs_route_stop` (`route_id`, `stop_id`),
    KEY `idx_trs_stop_seq` (`stop_id`, `stop_sequence`),
    CONSTRAINT `fk_trs_route` FOREIGN KEY (`route_id`) REFERENCES `transport_routes` (`route_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_trs_stop` FOREIGN KEY (`stop_id`) REFERENCES `transport_stops` (`stop_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";

if (!$conn->query($sqlRouteStops)) {
    die("Error creating transport_route_stops: " . $conn->error . "\n");
}
echo "Table verified/created: transport_route_stops\n";

// 2. Create transport_stop_aliases (separate from destination_aliases)
$sqlAliases = "CREATE TABLE IF NOT EXISTS `transport_stop_aliases` (
    `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `alias` VARCHAR(150) NOT NULL,
    `stop_id` VARCHAR(50) NOT NULL,
    `notes` VARCHAR(255) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_tsa_alias_stop` (`alias`, `stop_id`),
    KEY `idx_tsa_alias` (`alias`),
    KEY `idx_tsa_stop` (`stop_id`),
    CONSTRAINT `fk_tsa_stop` FOREIGN KEY (`stop_id`) REFERENCES `transport_stops` (`stop_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";

if (!$conn->query($sqlAliases)) {
    die("Error creating transport_stop_aliases: " . $conn->error . "\n");
}
echo "Table verified/created: transport_stop_aliases\n\n";

// 3. Load valid route_ids and stop_ids from MySQL to validate before insert
$validRoutes = [];
$res = $conn->query("SELECT route_id FROM transport_routes");
while ($r = $res->fetch_row()) {
    $validRoutes[$r[0]] = true;
}
$validStops = [];
$res = $conn->query("SELECT stop_id FROM transport_stops");
while ($r = $res->fetch_row()) {
    $validStops[$r[0]] = true;
}

// 4. Load trips.txt: trip_id => [route_id, direction_id]
echo "Reading trips.txt...\n";
$tripMap = [];
$fp = fopen($gtfsDir . 'trips.txt', 'r');
if (!$fp) die("Cannot open trips.txt\n");
$headers = fgetcsv($fp);
$idxTrip = array_search('trip_id', $headers);
$idxRoute = array_search('route_id', $headers);
$idxDir = array_search('direction_id', $headers);

while (($row = fgetcsv($fp)) !== false) {
    if (count($row) < 2) continue;
    $tid = trim((string)($row[$idxTrip] ?? ''));
    $rid = trim((string)($row[$idxRoute] ?? ''));
    $dir = (int)trim((string)($row[$idxDir] ?? '0'));
    if ($tid !== '' && $rid !== '') {
        $tripMap[$tid] = [$rid, $dir];
    }
}
fclose($fp);
echo "Loaded trips: " . count($tripMap) . "\n";

// 5. Scan stop_times.txt and deduplicate (route_id, direction_id, stop_id, stop_sequence)
echo "Reading stop_times.txt...\n";
$fp = fopen($gtfsDir . 'stop_times.txt', 'r');
if (!$fp) die("Cannot open stop_times.txt\n");
$headers = fgetcsv($fp);
$idxStTrip = array_search('trip_id', $headers);
$idxStStop = array_search('stop_id', $headers);
$idxStSeq = array_search('stop_sequence', $headers);

$seen = [];
$rejectedCount = 0;
$totalRows = 0;

while (($row = fgetcsv($fp)) !== false) {
    if (count($row) < 4) {
        $rejectedCount++;
        continue;
    }
    $totalRows++;
    $tid = trim((string)($row[$idxStTrip] ?? ''));
    $sid = trim((string)($row[$idxStStop] ?? ''));
    $seqRaw = trim((string)($row[$idxStSeq] ?? ''));

    if ($tid === '' || $sid === '' || $seqRaw === '' || !is_numeric($seqRaw)) {
        $rejectedCount++;
        continue;
    }
    if (!isset($tripMap[$tid])) {
        $rejectedCount++;
        continue;
    }
    [$rid, $dir] = $tripMap[$tid];
    if (!isset($validRoutes[$rid]) || !isset($validStops[$sid])) {
        $rejectedCount++;
        continue;
    }
    $seq = (int)$seqRaw;
    $key = $rid . '|' . $dir . '|' . $sid . '|' . $seq;
    if (!isset($seen[$key])) {
        $seen[$key] = [$rid, $dir, $sid, $seq];
    }
}
fclose($fp);
unset($tripMap);

echo "Scanned stop_times rows: $totalRows\n";
echo "Unique valid (route_id, direction_id, stop_id, stop_sequence): " . count($seen) . "\n";
echo "Rejected/invalid stop_times rows: $rejectedCount\n\n";

// 6. Populate transport_route_stops cleanly
echo "Populating transport_route_stops...\n";
$conn->query("TRUNCATE TABLE transport_route_stops");
$conn->begin_transaction();

$insertedCount = 0;
$batch = [];
$batchSize = 1000;

$flushBatch = function () use ($conn, &$batch, &$insertedCount) {
    if (empty($batch)) return;
    $placeholders = implode(',', array_fill(0, count($batch), '(?,?,?,?)'));
    $sql = "INSERT INTO transport_route_stops (route_id, direction_id, stop_id, stop_sequence) VALUES $placeholders";
    $stmt = $conn->prepare($sql);
    $types = str_repeat('sisi', count($batch));
    $params = [];
    foreach ($batch as $item) {
        $params[] = $item[0];
        $params[] = $item[1];
        $params[] = $item[2];
        $params[] = $item[3];
    }
    $stmt->bind_param($types, ...$params);
    $stmt->execute();
    $insertedCount += $stmt->affected_rows;
    $stmt->close();
    $batch = [];
};

foreach ($seen as $tuple) {
    $batch[] = $tuple;
    if (count($batch) >= $batchSize) {
        $flushBatch();
    }
}
$flushBatch();
$conn->commit();

echo "Inserted rows into transport_route_stops: $insertedCount\n\n";

// 7. Seed transport_stop_aliases
$aliases = [
    ['vadapalani', 'CMRL-213', 'Vadapalani Metro Station'],
    ['vadapalani metro', 'CMRL-213', 'Vadapalani Metro Station'],
    ['chennai central', 'CMRL-104', 'MGR Chennai Central Metro Station'],
    ['central metro', 'CMRL-104', 'MGR Chennai Central Metro Station'],
    ['mgr chennai central', 'CMRL-104', 'MGR Chennai Central Metro Station'],
    ['poonamallee bus stand', 'MTC-5821', 'Poonamallee Bus Terminus'],
    ['poonamallee bus terminus', 'MTC-5821', 'Poonamallee Bus Terminus'],
    ['light house', 'SRR-MLHS', 'Light House MRTS Station (Southern Railway)'],
    ['lighthouse', 'SRR-MLHS', 'Light House MRTS Station (Southern Railway)'],
];

$stmtAlias = $conn->prepare(
    "INSERT INTO transport_stop_aliases (alias, stop_id, notes)
     VALUES (?, ?, ?)
     ON DUPLICATE KEY UPDATE notes = VALUES(notes)"
);
$aliasCount = 0;
foreach ($aliases as [$alias, $sid, $note]) {
    if (!isset($validStops[$sid])) {
        echo "WARNING: Alias stop_id $sid not found in transport_stops, skipping.\n";
        continue;
    }
    $stmtAlias->bind_param('sss', $alias, $sid, $note);
    $stmtAlias->execute();
    $aliasCount++;
}
$stmtAlias->close();

echo "Upserted transport_stop_aliases rows: $aliasCount\n";
echo "=== MIGRATION COMPLETE ===\n";
