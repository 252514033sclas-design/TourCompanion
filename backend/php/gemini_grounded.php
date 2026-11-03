<?php
/**
 * Grounded AI Synthesis Helper for Semmozhi (Tour Companion)
 *
 * Uses Gemini 3.8 Flash (Thinking: medium) to synthesize concise,
 * natural responses strictly grounded on verified travel information.
 */

// Single Canonical Server Configuration
$canonicalConfig = 'C:/xampp/htdocs/tourcompanion/config_ai.php';
if (file_exists($canonicalConfig)) {
    require_once $canonicalConfig;
} elseif (file_exists(__DIR__ . '/config_ai.php')) {
    require_once __DIR__ . '/config_ai.php';
}

if (!defined('GEMINI_MODEL')) {
    define('GEMINI_MODEL', 'gemini-3.8-flash');
}
if (!defined('GEMINI_THINKING_LEVEL')) {
    define('GEMINI_THINKING_LEVEL', 'medium');
}
if (!defined('GEMINI_TIMEOUT_SECONDS')) {
    define('GEMINI_TIMEOUT_SECONDS', 3.5);
}
if (!defined('GEMINI_API_KEY')) {
    define('GEMINI_API_KEY', '');
}

/**
 * Calls Gemini 3.8 Flash with verified travel context and the user's question.
 * Returns the synthesized natural-language string on success, or null on any failure.
 *
 * @param string $question    User's question
 * @param string $topic       Retrieved tip topic
 * @param string $trustedFact Retrieved trusted travel tip answer text
 * @return string|null Natural answer on success, or null to trigger deterministic fallback
 */
function call_gemini_grounded(string $question, string $topic, string $trustedFact): ?string {
    $apiKey = defined('GEMINI_API_KEY') ? trim(GEMINI_API_KEY) : '';
    if ($apiKey === '' || $apiKey === 'AIzaSyDemoKeyReplaceWithYourActualKey') {
        // Missing or placeholder key: safely skip AI and fall back to trusted MySQL answer
        return null;
    }

    $systemInstruction = "You are Semmozhi, a friendly and concise virtual travel companion for travelers in India.\n"
        . "You must answer ONLY using the verified travel information supplied by the application.\n\n"
        . "Rules:\n"
        . "1. Answer in the same language as the traveler's question when supported (English, Tamil, Hindi).\n"
        . "2. Keep the response concise, normally 1-2 sentences.\n"
        . "3. Do not invent facts.\n"
        . "4. Do not invent: phone numbers, addresses, prices, opening hours, routes, train/bus schedules, laws, medical instructions, or emergency instructions.\n"
        . "5. Do not contradict the supplied trusted information.\n"
        . "6. Do not act as an emergency authority.\n"
        . "7. If the supplied fact does not sufficiently answer the question, state that the available travel information does not contain that specific detail.\n"
        . "8. Do not answer unrelated questions using invented information.";

    $userPrompt = "Topic:\n" . trim($topic) . "\n\n"
        . "Verified travel information:\n" . trim($trustedFact) . "\n\n"
        . "Traveler question:\n" . trim($question);

    $payload = [
        "system_instruction" => [
            "parts" => [
                ["text" => $systemInstruction]
            ]
        ],
        "contents" => [
            [
                "role" => "user",
                "parts" => [
                    ["text" => $userPrompt]
                ]
            ]
        ],
        "generationConfig" => [
            "thinkingConfig" => [
                "thinkingLevel" => GEMINI_THINKING_LEVEL
            ]
        ]
    ];

    $jsonPayload = json_encode($payload, JSON_UNESCAPED_UNICODE);
    if ($jsonPayload === false) {
        return null;
    }

    $url = "https://generativelanguage.googleapis.com/v1beta/models/" . urlencode(GEMINI_MODEL) . ":generateContent?key=" . urlencode($apiKey);

    $ch = curl_init($url);
    if ($ch === false) {
        return null;
    }

    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, $jsonPayload);
    curl_setopt($ch, CURLOPT_TIMEOUT, (int)ceil(GEMINI_TIMEOUT_SECONDS));
    curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 2);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, [
        'Content-Type: application/json; charset=utf-8',
        'User-Agent: TourCompanion-Backend/2.0'
    ]);

    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlErr = curl_error($ch);
    curl_close($ch);

    if ($response === false || $httpCode !== 200 || !empty($curlErr)) {
        // Safe graceful degradation: fallback to trusted MySQL answer
        return null;
    }

    $resData = json_decode($response, true);
    if (!is_array($resData)) {
        return null;
    }

    $candidates = $resData['candidates'] ?? [];
    if (empty($candidates) || !is_array($candidates)) {
        return null;
    }

    $content = $candidates[0]['content'] ?? [];
    $parts = $content['parts'] ?? [];
    if (empty($parts) || !is_array($parts)) {
        return null;
    }

    $generatedText = '';
    foreach ($parts as $part) {
        if (!empty($part['thought'])) {
            continue;
        }
        if (isset($part['text']) && is_string($part['text'])) {
            $generatedText .= $part['text'];
        }
    }

    $generatedText = trim($generatedText);
    if ($generatedText === '') {
        return null;
    }

    return $generatedText;
}
