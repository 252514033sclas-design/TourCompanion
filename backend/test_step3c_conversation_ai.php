<?php
declare(strict_types=1);

require_once __DIR__ . '/local_ai.php';

$passed = 0;
$failed = 0;

function assert_true(bool $cond, string $label, string $detail = ''): void {
    global $passed, $failed;
    if ($cond) {
        $passed++;
        echo "[PASS] $label\n";
    } else {
        $failed++;
        echo "[FAIL] $label" . ($detail !== '' ? " -> $detail" : "") . "\n";
    }
}

function post_ask(array $payload): array {
    $url = "http://127.0.0.1/tourcompanion/api/ask.php";
    $jsonPayload = json_encode($payload, JSON_UNESCAPED_UNICODE);

    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, $jsonPayload);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json; charset=utf-8']);
    curl_setopt($ch, CURLOPT_TIMEOUT, 30);

    $res = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return [
        'http_code' => $code,
        'raw' => $res,
        'json' => is_string($res) ? json_decode($res, true) : null
    ];
}

echo "=== UNIT TESTS: Prompt Builder & Grounding Rules ===\n";

// 1. Empty history
$pEmpty = build_gemma_user_content('Marina Beach', 'Open 24 hours.', 'When is it open?', 'English', []);
assert_true(
    !str_contains($pEmpty, 'CONVERSATION CONTEXT:') &&
    str_contains($pEmpty, "CURRENT USER QUESTION:\nWhen is it open?") &&
    str_contains($pEmpty, "Verified Facts:\nOpen 24 hours."),
    "1. Empty history omits context block and preserves Verified Facts + CURRENT USER QUESTION"
);

// 2. One user/assistant pair
$pPair = build_gemma_user_content(
    'Marina Beach',
    'Walk along promenade.',
    'What can I do there?',
    'English',
    [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => 'Marina Beach is a long beach in Chennai.']
    ]
);
assert_true(
    str_contains($pPair, "CONVERSATION CONTEXT:\nUser: Tell me about Marina Beach\nSemmozhi: Marina Beach is a long beach in Chennai.\nEND CONVERSATION CONTEXT") &&
    str_contains($pPair, "CURRENT USER QUESTION:\nWhat can I do there?"),
    "2. User/assistant pair formatted cleanly in CONVERSATION CONTEXT"
);

// 3. Multiple messages preserve order & 4. Current question separated
$pMulti = build_gemma_user_content(
    'Valluvar Kottam',
    'Monument in Chennai.',
    'What can I do there?',
    'English',
    [
        ['role' => 'user', 'text' => 'U1'],
        ['role' => 'assistant', 'text' => 'A1'],
        ['role' => 'user', 'text' => 'U2'],
        ['role' => 'assistant', 'text' => 'A2']
    ]
);
$posU1 = strpos($pMulti, "User: U1");
$posA1 = strpos($pMulti, "Semmozhi: A1");
$posU2 = strpos($pMulti, "User: U2");
$posA2 = strpos($pMulti, "Semmozhi: A2");
$posEnd = strpos($pMulti, "END CONVERSATION CONTEXT");
$posCur = strpos($pMulti, "CURRENT USER QUESTION:");
assert_true(
    $posU1 !== false && $posA1 > $posU1 && $posU2 > $posA1 && $posA2 > $posU2 && $posEnd > $posA2 && $posCur > $posEnd,
    "3 & 4. Chronological order preserved and CURRENT USER QUESTION separated from context"
);

// 5. System instruction treats history as context only, Verified Facts authoritative
$sysEn = build_gemma_system_instruction('en', false);
assert_true(
    str_contains($sysEn, 'CONVERSATION CONTEXT RULE') &&
    str_contains($sysEn, "Only 'Verified Facts' is authoritative"),
    "5. System instruction enforces Verified Facts authority over history"
);

// 6. Language rules preserved
$sysTa = build_gemma_system_instruction('ta', false);
$sysHi = build_gemma_system_instruction('hi', false);
assert_true(
    str_contains($sysTa, 'ONLY in Tamil script') && str_contains($sysHi, 'ONLY in Hindi'),
    "6. Tamil and Hindi language instructions preserved"
);

// 7. History limits remain 6 x 500
$eight = [];
for ($i = 1; $i <= 8; $i++) {
    $eight[] = ['role' => ($i % 2 === 1) ? 'user' : 'assistant', 'text' => "MSG{$i}_" . str_repeat('x', 550)];
}
$pCapped = build_gemma_user_content('Marina Beach', 'Fact', 'Q', 'English', $eight);
assert_true(
    !str_contains($pCapped, 'MSG1_') && !str_contains($pCapped, 'MSG2_') &&
    str_contains($pCapped, 'MSG3_') && str_contains($pCapped, 'MSG8_'),
    "7a. Prompt builder enforces 6-message cap"
);
$lines = explode("\n", $pCapped);
$maxLenOk = true;
foreach ($lines as $ln) {
    if (str_starts_with($ln, 'User: MSG') && mb_strlen($ln, 'UTF-8') > 506) {
        $maxLenOk = false;
    }
}
assert_true($maxLenOk, "7b. Prompt builder enforces 500-char per-message cap");

echo "\n=== LIVE CONVERSATIONAL API TESTS (http://127.0.0.1/tourcompanion/api/ask.php + Gemma) ===\n";

// Test A: Destination context
$tA1 = post_ask(['question' => 'Tell me about Marina Beach', 'user_id' => 1]);
$ansA1 = $tA1['json']['answer'] ?? '';
$tA2 = post_ask([
    'question' => 'What can I do there?',
    'user_id' => 1,
    'conversation_history' => [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => $ansA1]
    ]
]);
echo "Test A2 response: " . json_encode($tA2['json'], JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $tA2['http_code'] === 200 && ($tA2['json']['topic'] ?? '') === 'Marina Beach' && !empty($tA2['json']['answer']),
    "Test A. 'What can I do there?' resolves Marina Beach from conversation history"
);

// Test B: Destination change (Marina Beach -> Valluvar Kottam -> What can I do there?)
$tB2 = post_ask(['question' => 'Tell me about Valluvar Kottam', 'user_id' => 1]);
$ansB2 = $tB2['json']['answer'] ?? '';
$tB3 = post_ask([
    'question' => 'What can I do there?',
    'user_id' => 1,
    'conversation_history' => [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => $ansA1],
        ['role' => 'user', 'text' => 'Tell me about Valluvar Kottam'],
        ['role' => 'assistant', 'text' => $ansB2]
    ]
]);
echo "Test B3 response: " . json_encode($tB3['json'], JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $tB3['http_code'] === 200 && ($tB3['json']['topic'] ?? '') === 'Valluvar Kottam' && !empty($tB3['json']['answer']),
    "Test B. Destination switch resolves Valluvar Kottam (not Marina Beach)"
);

// Test C: Verified MySQL data wins over stale/wrong assistant statement in history
$tC = post_ask([
    'question' => 'What is the entry fee of Marina Beach?',
    'user_id' => 1,
    'conversation_history' => [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => 'Marina Beach entry fee is 999 rupees.']
    ]
]);
echo "Test C response: " . json_encode($tC['json'], JSON_UNESCAPED_UNICODE) . "\n";
$ansC = strtolower($tC['json']['answer'] ?? '');
assert_true(
    $tC['http_code'] === 200 && ($tC['json']['topic'] ?? '') === 'Marina Beach' && !str_contains($ansC, '999'),
    "Test C. Verified MySQL fact wins over stale assistant number (999 rejected/not reused)"
);

// Test D: Emergency query with conversation history
$tD = post_ask([
    'question' => 'Emergency help someone is following me',
    'user_id' => 1,
    'conversation_history' => [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => $ansA1]
    ]
]);
assert_true(
    $tD['http_code'] === 200 &&
    ($tD['json']['topic'] ?? '') === 'Emergency' &&
    ($tD['json']['ai_generated'] ?? true) === false &&
    str_contains($tD['json']['answer'] ?? '', '112'),
    "Test D. Deterministic emergency handling wins even with conversation history"
);

// Test E: Empty history works as before
$tE = post_ask(['question' => 'Tell me about Marina Beach', 'user_id' => 1]);
assert_true(
    $tE['http_code'] === 200 && ($tE['json']['topic'] ?? '') === 'Marina Beach' && !empty($tE['json']['answer']),
    "Test E. First-time question with empty history succeeds"
);

// Test F: No-match guard unchanged
$tF = post_ask([
    'question' => 'Tell me about Atlantis underwater city',
    'user_id' => 1,
    'conversation_history' => [
        ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
        ['role' => 'assistant', 'text' => $ansA1]
    ]
]);
assert_true(
    $tF['http_code'] === 200 &&
    ($tF['json']['ai_generated'] ?? true) === false &&
    str_contains($tF['json']['answer'] ?? '', "don't have a specific travel tip"),
    "Test F. No-match guard prevents Gemma hallucination even with history"
);

echo "\nSUMMARY: Passed=$passed Failed=$failed\n";
exit($failed > 0 ? 1 : 0);
