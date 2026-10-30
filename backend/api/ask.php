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

$question = trim($input['question'] ?? '');

if ($question === '') {
    http_response_code(400);

    echo json_encode([
        "status" => "error",
        "message" => "Question is required"
    ]);

    exit;
}

$words = preg_split('/\s+/', strtolower($question));

$ignoredWords = [
    "where", "what", "when", "why", "how",
    "can", "could", "should", "would",
    "is", "are", "the", "a", "an",
    "i", "me", "my", "you", "your",
    "do", "does", "please", "tell",
    "find", "get", "go", "need"
];

$specificWords = array_diff($words, $ignoredWords);

$sql = "SELECT id, topic, keywords, answer FROM travel_tips";

$result = $conn->query($sql);

$bestTip = null;
$bestScore = 0;

if ($result) {

    while ($row = $result->fetch_assoc()) {

        $keywords = array_map(
            'trim',
            explode(',', strtolower($row['keywords']))
        );

        $score = 0;

        foreach ($specificWords as $word) {

            $word = preg_replace('/[^a-z0-9]/', '', $word);

            if ($word === '') {
                continue;
            }

            foreach ($keywords as $keyword) {

                if ($word === $keyword) {
                    $score += 100;
                }

                if (rtrim($keyword, 's') === rtrim($word, 's')) {
                    $score += 80;
                }
            }
        }

        if ($score > $bestScore) {
            $bestScore = $score;
            $bestTip = $row;
        }
    }
}

if ($bestTip === null) {

    echo json_encode([
        "status" => "ok",
        "question" => $question,
        "answer" => "Sorry, I don't have a specific travel tip for that question yet."
    ]);

    exit;
}

echo json_encode([
    "status" => "ok",
    "question" => $question,
    "topic" => $bestTip['topic'],
    "answer" => $bestTip['answer']
]);
