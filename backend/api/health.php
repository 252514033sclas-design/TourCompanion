<?php

header('Content-Type: application/json');

require_once __DIR__ . '/../config.php';

echo json_encode([
    'status' => 'ok',
    'message' => 'TourCompanion API is running',
    'database' => 'connected'
]);