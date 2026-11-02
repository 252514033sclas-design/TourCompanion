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
$status = trim($input['status'] ?? 'safe');

if ($userId <= 0) {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Valid user_id is required"
    ]);

    exit;
}

$allowedStatuses = [
    "safe",
    "need_help"
];

if (!in_array($status, $allowedStatuses, true)) {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Invalid safety status"
    ]);

    exit;
}

$userCheck = $conn->prepare(
    "SELECT id FROM users WHERE id = ? LIMIT 1"
);

$userCheck->bind_param("i", $userId);
$userCheck->execute();

$userResult = $userCheck->get_result();

if ($userResult->num_rows === 0) {
    $userCheck->close();

    http_response_code(404);

    echo json_encode([
        "status" => "error",
        "message" => "User not found"
    ]);

    exit;
}

$userCheck->close();

$stmt = $conn->prepare(
    "INSERT INTO safety_checkins (user_id, status)
     VALUES (?, ?)"
);

$stmt->bind_param("is", $userId, $status);

if ($stmt->execute()) {

    echo json_encode([
        "status" => "ok",
        "message" => "Safety check-in saved successfully",
        "checkin_id" => $stmt->insert_id
    ]);

} else {

    http_response_code(500);

    echo json_encode([
        "status" => "error",
        "message" => "Unable to save safety check-in"
    ]);
}

$stmt->close();