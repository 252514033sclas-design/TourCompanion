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

$name = trim($input['name'] ?? '');
$email = strtolower(trim($input['email'] ?? ''));
$password = $input['password'] ?? '';

if ($name === '' || $email === '' || $password === '') {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Name, email and password are required"
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

if (strlen($password) < 6) {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Password must be at least 6 characters"
    ]);

    exit;
}

$check = $conn->prepare(
    "SELECT id FROM users WHERE email = ?"
);

$check->bind_param("s", $email);
$check->execute();
$check->store_result();

if ($check->num_rows > 0) {
    $check->close();

    http_response_code(409);

    echo json_encode([
        "status" => "error",
        "message" => "Email is already registered"
    ]);

    exit;
}

$check->close();

$passwordHash = password_hash(
    $password,
    PASSWORD_DEFAULT
);

$stmt = $conn->prepare(
    "INSERT INTO users (name, email, password_hash)
     VALUES (?, ?, ?)"
);

$stmt->bind_param(
    "sss",
    $name,
    $email,
    $passwordHash
);

if ($stmt->execute()) {

    echo json_encode([
        "status" => "ok",
        "message" => "Account created successfully",
        "user_id" => $stmt->insert_id
    ]);

} else {

    http_response_code(500);

    echo json_encode([
        "status" => "error",
        "message" => "Unable to create account"
    ]);
}

$stmt->close();