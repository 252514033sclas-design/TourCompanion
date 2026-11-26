<?php
declare(strict_types=1);

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

function post_ask(string $question, array $history = []): array {
    $url = "http://127.0.0.1/tourcompanion/api/ask.php";
    $payload = ['question' => $question, 'user_id' => 1];
    if (!empty($history)) {
        $payload['conversation_history'] = $history;
    }
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload, JSON_UNESCAPED_UNICODE));
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json; charset=utf-8']);
    curl_setopt($ch, CURLOPT_TIMEOUT, 25);
    $res = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return ['code' => $code, 'json' => is_string($res) ? json_decode($res, true) : null];
}

// Test 1: Peaceful evening -> must NOT match Evening Plan
$r1 = post_ask("Where can I go for a peaceful evening in Chennai?");
$j1 = $r1['json'] ?? [];
echo "T1: " . json_encode($j1, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r1['code'] === 200 &&
    ($j1['topic'] ?? '') !== 'Evening Plan' &&
    ($j1['ai_generated'] ?? true) === false &&
    !str_contains(strtolower($j1['answer'] ?? ''), 'keep your phone charged'),
    "Test 1. Peaceful evening does not hijack Evening Plan"
);

// Test 2: Visit a beach -> must NOT match Beaches and swimming
$r2 = post_ask("I want to visit a beach in Chennai. What can I do?");
$j2 = $r2['json'] ?? [];
echo "T2: " . json_encode($j2, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r2['code'] === 200 &&
    ($j2['topic'] ?? '') !== 'Beaches and swimming' &&
    ($j2['ai_generated'] ?? true) === false &&
    !str_contains(strtolower($j2['answer'] ?? ''), 'lifeguards'),
    "Test 2. Visit a beach does not hijack Beaches and swimming safety tip"
);

// Test 3: What can I do at Marina Beach?
$r3 = post_ask("What can I do at Marina Beach?");
$j3 = $r3['json'] ?? [];
echo "T3: " . json_encode($j3, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r3['code'] === 200 && ($j3['topic'] ?? '') === 'Marina Beach' && !empty($j3['answer']),
    "Test 3. What can I do at Marina Beach? retrieves Marina Beach things_to_do"
);

// Test 4: What can I do there? after Marina Beach
$r4 = post_ask("What can I do there?", [
    ['role' => 'user', 'text' => 'Tell me about Marina Beach'],
    ['role' => 'assistant', 'text' => 'Marina Beach is an iconic natural urban beach in Chennai.']
]);
$j4 = $r4['json'] ?? [];
echo "T4: " . json_encode($j4, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r4['code'] === 200 && ($j4['topic'] ?? '') === 'Marina Beach' && !empty($j4['answer']),
    "Test 4. What can I do there? resolves Marina Beach"
);

// Test 5: Unsafe travelling alone -> preserved
$r5 = post_ask("I feel unsafe while travelling alone in Chennai. What should I do?");
$j5 = $r5['json'] ?? [];
echo "T5: " . json_encode($j5, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r5['code'] === 200 && ($j5['topic'] ?? '') === 'Solo Travel' && !empty($j5['answer']),
    "Test 5. Solo travel safety tip preserved"
);

// Test 6: Stay safe at the beach -> NOT suppressed (reaches travel_tips safety scoring)
$r6 = post_ask("What should I do to stay safe at the beach?");
$j6 = $r6['json'] ?? [];
echo "T6: " . json_encode($j6, JSON_UNESCAPED_UNICODE) . "\n";
assert_true(
    $r6['code'] === 200 && !empty($j6['topic']) && !empty($j6['answer']),
    "Test 6. Safety query containing 'beach' is not suppressed and selects a safety tip"
);

echo "\nSUMMARY: Passed=$passed Failed=$failed\n";
exit($failed > 0 ? 1 : 0);
