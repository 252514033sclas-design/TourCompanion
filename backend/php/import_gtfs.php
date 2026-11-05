<?php
/**
 * GTFS Static Transport Data Importer for Tour Companion
 *
 * Imports agency, routes, stops, and transfers from the ChennaiGTFS dataset.
 * Designed to be safe, repeatable, duplicate-free, and transactional.
 */

if (php_sapi_name() !== 'cli') {
    die("This script can only be run from the command line.\n");
}

require_once __DIR__ . '/config.php';

// Path to extracted GTFS directory
$gtfsDir = 'C:/Users/SCAD LAB/gtfs_work/extracted/';
if (!is_dir($gtfsDir)) {
    die("GTFS extracted directory not found at: $gtfsDir\n");
}

echo "Starting Tour Companion GTFS Import...\n";
echo "Database: $dbname\n";
echo "GTFS Directory: $gtfsDir\n\n";

// 1. Create Tables
$tables = [
    "transport_agencies" => "CREATE TABLE IF NOT EXISTS `transport_agencies` (
        `agency_id` VARCHAR(30) NOT NULL,
        `agency_name` VARCHAR(150) NOT NULL,
        `agency_url` VARCHAR(255) DEFAULT NULL,
        `agency_timezone` VARCHAR(50) DEFAULT 'Asia/Kolkata',
        `agency_phone` VARCHAR(50) DEFAULT NULL,
        `agency_lang` VARCHAR(10) DEFAULT NULL,
        `agency_fare_url` VARCHAR(255) DEFAULT NULL,
        `agency_email` VARCHAR(100) DEFAULT NULL,
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`agency_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",

    "transport_stops" => "CREATE TABLE IF NOT EXISTS `transport_stops` (
        `stop_id` VARCHAR(50) NOT NULL,
        `stop_name` VARCHAR(200) NOT NULL,
        `stop_lat` DECIMAL(10, 7) NOT NULL,
        `stop_lon` DECIMAL(10, 7) NOT NULL,
        `stop_desc` VARCHAR(255) DEFAULT NULL,
        `zone_id` VARCHAR(50) DEFAULT NULL,
        `location_type` INT NOT NULL DEFAULT 0,
        `parent_station` VARCHAR(50) DEFAULT NULL,
        `platform_code` VARCHAR(20) DEFAULT NULL,
        `stop_code` VARCHAR(20) DEFAULT NULL,
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`stop_id`),
        KEY `idx_stops_lat_lon` (`stop_lat`, `stop_lon`),
        KEY `idx_stops_name` (`stop_name`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",

    "transport_routes" => "CREATE TABLE IF NOT EXISTS `transport_routes` (
        `route_id` VARCHAR(50) NOT NULL,
        `agency_id` VARCHAR(30) DEFAULT NULL,
        `route_short_name` VARCHAR(50) NOT NULL,
        `route_long_name` VARCHAR(255) NOT NULL,
        `route_type` INT NOT NULL,
        `route_desc` VARCHAR(100) DEFAULT NULL,
        `route_color` VARCHAR(10) DEFAULT NULL,
        `route_text_color` VARCHAR(10) DEFAULT NULL,
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`route_id`),
        KEY `idx_routes_agency` (`agency_id`),
        KEY `idx_routes_short_name` (`route_short_name`),
        CONSTRAINT `fk_routes_agency` FOREIGN KEY (`agency_id`) REFERENCES `transport_agencies` (`agency_id`) ON DELETE SET NULL ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",

    "transport_transfers" => "CREATE TABLE IF NOT EXISTS `transport_transfers` (
        `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
        `from_stop_id` VARCHAR(50) NOT NULL,
        `to_stop_id` VARCHAR(50) NOT NULL,
        `from_route_id` VARCHAR(50) DEFAULT NULL,
        `to_route_id` VARCHAR(50) DEFAULT NULL,
        `transfer_type` INT NOT NULL DEFAULT 0,
        `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`id`),
        KEY `idx_transfer_from_stop` (`from_stop_id`),
        KEY `idx_transfer_to_stop` (`to_stop_id`),
        KEY `idx_transfer_from_route` (`from_route_id`),
        KEY `idx_transfer_to_route` (`to_route_id`),
        UNIQUE KEY `uniq_transfer` (`from_stop_id`, `to_stop_id`, `from_route_id`, `to_route_id`),
        CONSTRAINT `fk_transfer_from_stop` FOREIGN KEY (`from_stop_id`) REFERENCES `transport_stops` (`stop_id`) ON DELETE CASCADE,
        CONSTRAINT `fk_transfer_to_stop` FOREIGN KEY (`to_stop_id`) REFERENCES `transport_stops` (`stop_id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"
];

foreach ($tables as $name => $sql) {
    if (!$conn->query($sql)) {
        die("Error creating table $name: " . $conn->error . "\n");
    }
    echo "Table verified/created: $name\n";
}
echo "\n";

// Helper to sanitize nullable string
function nullIfEmpty(?string $val): ?string {
    if ($val === null) return null;
    $trimmed = trim($val);
    return $trimmed === '' ? null : $trimmed;
}

// 2. Import Agencies
echo "Importing transport_agencies...\n";
$fp = fopen($gtfsDir . 'agency.txt', 'r');
$headers = fgetcsv($fp);
$conn->begin_transaction();
$stmt = $conn->prepare(
    "INSERT INTO transport_agencies (agency_id, agency_name, agency_url, agency_timezone, agency_phone, agency_lang, agency_fare_url, agency_email)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE 
       agency_name = VALUES(agency_name),
       agency_url = VALUES(agency_url),
       agency_timezone = VALUES(agency_timezone),
       agency_phone = VALUES(agency_phone),
       agency_lang = VALUES(agency_lang),
       agency_fare_url = VALUES(agency_fare_url),
       agency_email = VALUES(agency_email)"
);

$agencyCount = 0;
while (($row = fgetcsv($fp)) !== false) {
    if (count($row) === 1 && $row[0] === null) continue;
    $d = array_combine($headers, $row);
    $agencyId = trim($d['agency_id'] ?? '');
    $agencyName = trim($d['agency_name'] ?? '');
    $agencyUrl = nullIfEmpty($d['agency_url'] ?? null);
    $agencyTz = nullIfEmpty($d['agency_timezone'] ?? null) ?? 'Asia/Kolkata';
    $agencyPhone = nullIfEmpty($d['agency_phone'] ?? null);
    $agencyLang = nullIfEmpty($d['agency_lang'] ?? null);
    $agencyFareUrl = nullIfEmpty($d['agency_fare_url'] ?? null);
    $agencyEmail = nullIfEmpty($d['agency_email'] ?? null);

    $stmt->bind_param("ssssssss", $agencyId, $agencyName, $agencyUrl, $agencyTz, $agencyPhone, $agencyLang, $agencyFareUrl, $agencyEmail);
    $stmt->execute();
    $agencyCount++;
}
$stmt->close();
fclose($fp);
$conn->commit();
echo "Imported agencies: $agencyCount\n\n";

// 3. Import Stops
echo "Importing transport_stops...\n";
$fp = fopen($gtfsDir . 'stops.txt', 'r');
$headers = fgetcsv($fp);
$conn->begin_transaction();
$stmt = $conn->prepare(
    "INSERT INTO transport_stops (stop_id, stop_name, stop_lat, stop_lon, stop_desc, zone_id, location_type, parent_station, platform_code, stop_code)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
       stop_name = VALUES(stop_name),
       stop_lat = VALUES(stop_lat),
       stop_lon = VALUES(stop_lon),
       stop_desc = VALUES(stop_desc),
       zone_id = VALUES(zone_id),
       location_type = VALUES(location_type),
       parent_station = VALUES(parent_station),
       platform_code = VALUES(platform_code),
       stop_code = VALUES(stop_code)"
);

$stopCount = 0;
$batchSize = 500;
while (($row = fgetcsv($fp)) !== false) {
    if (count($row) === 1 && $row[0] === null) continue;
    $d = array_combine($headers, $row);

    $stopId = trim($d['stop_id'] ?? '');
    $stopName = trim($d['stop_name'] ?? '');
    $stopLat = (float)($d['stop_lat'] ?? 0);
    $stopLon = (float)($d['stop_lon'] ?? 0);
    $stopDesc = nullIfEmpty($d['stop_desc'] ?? null);
    $zoneId = nullIfEmpty($d['zone_id'] ?? null);
    $locType = (int)($d['location_type'] ?? 0);
    $parentStation = nullIfEmpty($d['parent_station'] ?? null);
    $platformCode = nullIfEmpty($d['platform_code'] ?? null);
    $stopCode = nullIfEmpty($d['stop_code'] ?? null);

    $stmt->bind_param("ssddssisss", $stopId, $stopName, $stopLat, $stopLon, $stopDesc, $zoneId, $locType, $parentStation, $platformCode, $stopCode);
    $stmt->execute();
    $stopCount++;

    if ($stopCount % $batchSize === 0) {
        $conn->commit();
        $conn->begin_transaction();
    }
}
$conn->commit();
$stmt->close();
fclose($fp);
echo "Imported stops: $stopCount\n\n";

// 4. Import Routes
echo "Importing transport_routes...\n";
$fp = fopen($gtfsDir . 'routes.txt', 'r');
$headers = fgetcsv($fp);
$conn->begin_transaction();
$stmt = $conn->prepare(
    "INSERT INTO transport_routes (route_id, agency_id, route_short_name, route_long_name, route_type, route_desc, route_color, route_text_color)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
       agency_id = VALUES(agency_id),
       route_short_name = VALUES(route_short_name),
       route_long_name = VALUES(route_long_name),
       route_type = VALUES(route_type),
       route_desc = VALUES(route_desc),
       route_color = VALUES(route_color),
       route_text_color = VALUES(route_text_color)"
);

$routeCount = 0;
while (($row = fgetcsv($fp)) !== false) {
    if (count($row) === 1 && $row[0] === null) continue;
    $d = array_combine($headers, $row);

    $routeId = trim($d['route_id'] ?? '');
    $agencyId = nullIfEmpty($d['agency_id'] ?? null);
    $shortName = trim($d['route_short_name'] ?? '');
    $longName = trim($d['route_long_name'] ?? '');
    $routeType = (int)($d['route_type'] ?? 3);
    $routeDesc = nullIfEmpty($d['route_desc'] ?? null);
    $routeColor = nullIfEmpty($d['route_color'] ?? null);
    $routeTextColor = nullIfEmpty($d['route_text_color'] ?? null);

    $stmt->bind_param("ssssisss", $routeId, $agencyId, $shortName, $longName, $routeType, $routeDesc, $routeColor, $routeTextColor);
    $stmt->execute();
    $routeCount++;

    if ($routeCount % $batchSize === 0) {
        $conn->commit();
        $conn->begin_transaction();
    }
}
$conn->commit();
$stmt->close();
fclose($fp);
echo "Imported routes: $routeCount\n\n";

// 5. Import Transfers
echo "Importing transport_transfers...\n";
$fp = fopen($gtfsDir . 'transfers.txt', 'r');
$headers = fgetcsv($fp);
$conn->begin_transaction();
$stmt = $conn->prepare(
    "INSERT INTO transport_transfers (from_stop_id, to_stop_id, from_route_id, to_route_id, transfer_type)
     VALUES (?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
       transfer_type = VALUES(transfer_type)"
);

$transferCount = 0;
while (($row = fgetcsv($fp)) !== false) {
    if (count($row) === 1 && $row[0] === null) continue;
    $d = array_combine($headers, $row);

    $fromStop = trim($d['from_stop_id'] ?? '');
    $toStop = trim($d['to_stop_id'] ?? '');
    $fromRoute = nullIfEmpty($d['from_route_id'] ?? null);
    $toRoute = nullIfEmpty($d['to_route_id'] ?? null);
    $transType = (int)($d['transfer_type'] ?? 0);

    $stmt->bind_param("ssssi", $fromStop, $toStop, $fromRoute, $toRoute, $transType);
    $stmt->execute();
    $transferCount++;
}
$conn->commit();
$stmt->close();
fclose($fp);
echo "Imported transfers: $transferCount\n\n";

echo "=== GTFS IMPORT COMPLETE ===\n";

