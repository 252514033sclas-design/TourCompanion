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

$email = strtolower(trim($input['email'] ?? ''));
$password = $input['password'] ?? '';

if ($email === '' || $password === '') {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Email and password are required"
    ]);

    exit;
}

if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Invalid email address"
    ]);

    exit;
}

$stmt = $conn->prepare(
    "SELECT id, name, email, password_hash, language
     FROM users
     WHERE email = ?
     LIMIT 1"
);

$stmt->bind_param("s", $email);
$stmt->execute();

$result = $stmt->get_result();

if ($result->num_rows === 0) {
    $stmt->close();

    http_response_code(401);

    echo json_encode([
        "status" => "error",
        "message" => "Invalid email or password"
    ]);

    exit;
}

$user = $result->fetch_assoc();

$stmt->close();

if (!password_verify($password, $user['password_hash'])) {

    http_response_code(401);

    echo json_encode([
        "status" => "error",
        "message" => "Invalid email or password"
    ]);

    exit;
}

echo json_encode([
    "status" => "ok",
    "message" => "Login successful",
    "user" => [
        "id" => $user['id'],
        "name" => $user['name'],
        "email" => $user['email'],
        "language" => $user['language']
    ]
]);