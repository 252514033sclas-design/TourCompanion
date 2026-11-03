<?php

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode([
        "status" => "error",
        "message" => "Only POST requests are allowed"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$input = json_decode(file_get_contents("php://input"), true);

$question = trim($input['question'] ?? '');
$userId = (int)($input['user_id'] ?? 0);

if ($question === '') {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Question is required"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * Normalizes text while strictly preserving Unicode letters and marks (English, Tamil, Hindi, etc.).
 */
function normalize_text(string $str): string {
    $str = mb_strtolower($str, 'UTF-8');
    // Replace non-letter, non-mark, non-number characters with spaces
    $str = preg_replace('/[^\p{L}\p{M}\p{N}\s]+/u', ' ', $str);
    $str = preg_replace('/\s+/u', ' ', $str);
    return trim($str);
}

$cleanQ = normalize_text($question);
$qTokens = array_values(array_filter(explode(' ', $cleanQ), fn($t) => $t !== ''));
$paddedQ = ' ' . $cleanQ . ' ';

// -------------------------------------------------------------
// Emergency / Distress Preemption
// -------------------------------------------------------------
$distressPhrases = [
    'sos', '112', 'save me', 'following me', 'followed me', 'being followed',
    'chasing me', 'someone is following', 'robbed', 'robbery', 'mugged',
    'attack', 'attacked', 'assault', 'assaulted', 'kidnap', 'kidnapped',
    'harass', 'harassed', 'harassment', 'threat', 'threatened',
    'accident', 'injured', 'bleeding', 'unconscious', 'stalked', 'stalking',
    'காப்பாற்றுங்கள்', 'உதவி', 'बचाओ', 'मदद', 'आपातकाल'
];

$isEmergency = false;
foreach ($distressPhrases as $dp) {
    if (strpos($paddedQ, ' ' . $dp . ' ') !== false || strpos($cleanQ, $dp) !== false) {
        $isEmergency = true;
        break;
    }
}

// Short query containing urgent distress word
if (!$isEmergency && count($qTokens) <= 4) {
    $urgentWords = ['help', 'உதவி', 'மதद', 'मदद', 'emergency'];
    foreach ($urgentWords as $uw) {
        if (in_array($uw, $qTokens, true)) {
            $isEmergency = true;
            break;
        }
    }
}

// English stop words (do not strip non-English words)
$stopWords = [
    "where", "what", "when", "why", "how", "can", "could", "should", "would",
    "is", "are", "the", "a", "an", "i", "me", "my", "you", "your", "do",
    "does", "did", "please", "tell", "to", "of", "in", "for", "on", "at",
    "there", "this", "that", "it", "and", "or", "with", "be", "about",
    "near", "any", "some", "get", "go", "from", "we", "us", "have", "has",
    "will", "am", "was", "were", "so", "if", "not", "no"
];

$contentTokens = array_values(array_filter($qTokens, fn($t) => !in_array($t, $stopWords, true)));

$sql = "SELECT id, topic, keywords, answer FROM travel_tips";
$result = $conn->query($sql);

$bestTip = null;
$bestScore = 0;

// Weak/common generic words that receive reduced weight
$weakWords = ['help', 'find', 'place', 'safe', 'person', 'public', 'plan', 'visit', 'travel', 'trip'];

if ($result) {
    while ($row = $result->fetch_assoc()) {
        $topic = $row['topic'];
        $normTopic = normalize_text($topic);
        $normTopicPadded = ' ' . $normTopic . ' ';

        // Fast-path for emergency
        if ($isEmergency && strtolower($topic) === 'emergency') {
            $bestTip = $row;
            $bestScore = 9999;
            break;
        }

        $keywords = array_map('trim', explode(',', $row['keywords']));
        $score = 0;

        foreach ($keywords as $kw) {
            $normKw = normalize_text($kw);
            if ($normKw === '') continue;

            $kwTokens = array_values(array_filter(explode(' ', $normKw), fn($t) => $t !== ''));
            $kwTokenCount = count($kwTokens);

            if ($kwTokenCount > 1) {
                // Multi-word phrase matching (highest specificity)
                if (strpos($paddedQ, ' ' . $normKw . ' ') !== false) {
                    $score += 150 * $kwTokenCount;
                }
            } else {
                // Single keyword matching
                if (in_array($normKw, $contentTokens, true)) {
                    if (in_array($normKw, $weakWords, true)) {
                        $score += 20;
                    } else {
                        $score += 70;
                    }
                } else {
                    // Singular/plural check for English
                    foreach ($contentTokens as $ct) {
                        if (mb_strlen($ct, 'UTF-8') > 3 && rtrim($ct, 's') === rtrim($normKw, 's')) {
                            $score += 50;
                            break;
                        }
                    }
                }
            }
        }

        // Topic bonus
        $topicTokens = array_values(array_filter(explode(' ', $normTopic), fn($t) => $t !== ''));
        if (count($topicTokens) > 1 && strpos($paddedQ, $normTopicPadded) !== false) {
            $score += 80;
        } else {
            foreach ($topicTokens as $tt) {
                if (in_array($tt, $contentTokens, true) && !in_array($tt, $stopWords, true)) {
                    $score += 40;
                }
            }
        }

        if ($score > $bestScore) {
            $bestScore = $score;
            $bestTip = $row;
        }
    }
}

$MIN_SCORE = 50;

if ($bestTip === null || $bestScore < $MIN_SCORE) {
    $answer = "Sorry, I don't have a specific travel tip for that question yet.";

    $stmt = $conn->prepare(
        "INSERT INTO query_logs (user_id, question, answer)
         VALUES (NULLIF(?, 0), ?, ?)"
    );
    $stmt->bind_param("iss", $userId, $question, $answer);
    $stmt->execute();
    $stmt->close();

    echo json_encode([
        "status" => "ok",
        "question" => $question,
        "answer" => $answer
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$answer = $bestTip['answer'];

$stmt = $conn->prepare(
    "INSERT INTO query_logs (user_id, question, answer)
     VALUES (NULLIF(?, 0), ?, ?)"
);
$stmt->bind_param("iss", $userId, $question, $answer);
$stmt->execute();
$stmt->close();

echo json_encode([
    "status" => "ok",
    "question" => $question,
    "topic" => $bestTip['topic'],
    "answer" => $answer
], JSON_UNESCAPED_UNICODE);
