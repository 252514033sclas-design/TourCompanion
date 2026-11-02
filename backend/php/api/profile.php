<?php

header('Content-Type: application/json');

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
    "SELECT id, name, email, language, created_at
     FROM users
     WHERE id = ?
     LIMIT 1"
);

$stmt->bind_param("i", $userId);
$stmt->execute();

$result = $stmt->get_result();

if ($result->num_rows === 0) {
    $stmt->close();

    http_response_code(404);

    echo json_encode([
        "status" => "error",
        "message" => "User not found"
    ]);

    exit;
}

$user = $result->fetch_assoc();

$stmt->close();

echo json_encode([
    "status" => "ok",
    "user" => [
        "id" => $user['id'],
        "name" => $user['name'],
        "email" => $user['email'],
        "language" => $user['language'],
        "created_at" => $user['created_at']
    ]
]);