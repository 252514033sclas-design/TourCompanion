<?php

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);

    echo json_encode([
        "status" => "error",
        "message" => "Only POST requests are allowed"
    ]);

    exit;
}

$input = json_decode(file_get_contents("php://input"), true);

$userId = (int)($input['user_id'] ?? 0);

if ($userId <= 0) {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Valid user_id is required"
    ]);

    exit;
}

$stmt = $conn->prepare(
    "SELECT id, status, checked_at
     FROM safety_checkins
     WHERE user_id = ?
     ORDER BY checked_at DESC
     LIMIT 1"
);

$stmt->bind_param("i", $userId);
$stmt->execute();

$result = $stmt->get_result();

if ($result->num_rows === 0) {
    $stmt->close();

    echo json_encode([
        "status" => "ok",
        "message" => "No safety check-in found",
        "checkin" => null
    ]);

    exit;
}

$checkin = $result->fetch_assoc();

$stmt->close();

echo json_encode([
    "status" => "ok",
    "checkin" => [
        "id" => $checkin['id'],
        "status" => $checkin['status'],
        "checked_at" => $checkin['checked_at']
    ]
]);