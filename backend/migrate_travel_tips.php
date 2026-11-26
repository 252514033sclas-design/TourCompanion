<?php
/**
 * Idempotent Travel Tips Migration Script
 * Synchronizes the complete Android tips.json catalog into MySQL tour_companion.travel_tips.
 */

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/config.php';

$jsonPath = 'C:/Users/SCAD LAB/AndroidStudioProjects/TourCompanion/app/src/main/res/raw/tips.json';

if (!file_exists($jsonPath)) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Source tips.json not found at: $jsonPath"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$rawJson = file_get_contents($jsonPath);
$data = json_decode($rawJson, true);

if (!isset($data['categories']) || !is_array($data['categories'])) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Invalid tips.json format: categories array missing"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Prepare lookup and write statements
$checkStmt = $conn->prepare("SELECT id FROM travel_tips WHERE LOWER(topic) = LOWER(?) LIMIT 1");
$updateStmt = $conn->prepare("UPDATE travel_tips SET keywords = ?, answer = ? WHERE id = ?");
$insertStmt = $conn->prepare("INSERT INTO travel_tips (topic, keywords, answer) VALUES (?, ?, ?)");

$totalCatalog = 0;
$updatedCount = 0;
$insertedCount = 0;
$skippedCount = 0;

$conn->begin_transaction();

try {
    foreach ($data['categories'] as $category) {
        $tips = $category['tips'] ?? [];
        foreach ($tips as $tip) {
            $totalCatalog++;
            $topic = trim($tip['topic'] ?? '');
            $keywords = trim($tip['keywords'] ?? '');
            $answers = $tip['answers'] ?? [];
            $answer = trim($answers[0] ?? '');

            if ($topic === '' || $answer === '') {
                $skippedCount++;
                continue;
            }

            // Check if topic already exists in MySQL
            $checkStmt->bind_param("s", $topic);
            $checkStmt->execute();
            $result = $checkStmt->get_result();

            if ($existing = $result->fetch_assoc()) {
                // Topic exists: update keywords and answer to match latest catalog
                $existingId = (int)$existing['id'];
                $updateStmt->bind_param("ssi", $keywords, $answer, $existingId);
                $updateStmt->execute();
                $updatedCount++;
            } else {
                // New topic: insert cleanly
                $insertStmt->bind_param("sss", $topic, $keywords, $answer);
                $insertStmt->execute();
                $insertedCount++;
            }
        }
    }

    $conn->commit();
} catch (Exception $e) {
    $conn->rollback();
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Migration failed: " . $e->getMessage()
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$checkStmt->close();
$updateStmt->close();
$insertStmt->close();

// Query final count
$countRes = $conn->query("SELECT COUNT(*) AS total FROM travel_tips")->fetch_assoc();
$finalTotal = (int)$countRes['total'];

echo json_encode([
    "status" => "ok",
    "message" => "Travel tips synchronized successfully",
    "total_in_json" => $totalCatalog,
    "updated_existing" => $updatedCount,
    "inserted_new" => $insertedCount,
    "skipped_invalid" => $skippedCount,
    "final_mysql_count" => $finalTotal
], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);

