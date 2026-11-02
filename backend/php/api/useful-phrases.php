<?php

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);

    echo json_encode([
        "status" => "error",
        "message" => "Only GET requests are allowed"
    ], JSON_UNESCAPED_UNICODE);

    exit;
}

$sql = "SELECT id, language, category, phrase, meaning
        FROM useful_phrases
        ORDER BY language, category, id";

$result = $conn->query($sql);

if (!$result) {
    http_response_code(500);

    echo json_encode([
        "status" => "error",
        "message" => "Unable to fetch useful phrases"
    ], JSON_UNESCAPED_UNICODE);

    exit;
}

$phrases = [];

while ($row = $result->fetch_assoc()) {
    $phrases[] = $row;
}

$response = [
    "status" => "ok",
    "count" => count($phrases),
    "data" => $phrases
];

echo json_encode(
    $response,
    JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE
);