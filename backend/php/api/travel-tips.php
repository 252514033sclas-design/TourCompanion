<?php

header('Content-Type: application/json');

require_once __DIR__ . '/../config.php';

$sql = "SELECT id, topic, keywords, answer FROM travel_tips";

$result = $conn->query($sql);

$travelTips = [];

if ($result) {
    while ($row = $result->fetch_assoc()) {
        $travelTips[] = $row;
    }
}

echo json_encode([
    "status" => "ok",
    "data" => $travelTips
]);