<?php
declare(strict_types=1);

/**
 * Local AI Synthesis Helper for Semmozhi (Tour Companion)
 *
 * Connects to a locally running llama-server (Gemma 3 4B Instruct Q4_K_M)
 * via localhost-only HTTP endpoint (http://127.0.0.1:8080/v1/chat/completions).
 *
 * Provides natural-language synthesis strictly grounded on verified facts
 * retrieved from MySQL or official transport providers.
 */

if (!defined('LOCAL_AI_ENDPOINT')) {
    define('LOCAL_AI_ENDPOINT', 'http://127.0.0.1:8080/v1/chat/completions');
}
if (!defined('LOCAL_AI_HEALTH_ENDPOINT')) {
    define('LOCAL_AI_HEALTH_ENDPOINT', 'http://127.0.0.1:8080/health');
}
if (!defined('LOCAL_AI_CONNECT_TIMEOUT_SEC')) {
    define('LOCAL_AI_CONNECT_TIMEOUT_SEC', 2);
}
if (!defined('LOCAL_AI_TIMEOUT_SEC')) {
    define('LOCAL_AI_TIMEOUT_SEC', 15);
}
if (!defined('LOCAL_AI_MAX_TOKENS')) {
    define('LOCAL_AI_MAX_TOKENS', 40);
}
if (!defined('LOCAL_AI_TEMPERATURE')) {
    define('LOCAL_AI_TEMPERATURE', 0.1);
}

$GLOBALS['LOCAL_AI_LAST_REJECTION_REASON'] = null;

/**
 * Checks if the local llama-server is alive and ready.
 */
function is_local_ai_available(): bool {
    $ch = curl_init(LOCAL_AI_HEALTH_ENDPOINT);
    if ($ch === false) return false;

    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, 1);
    curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 1);
    
    $res = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($code !== 200 || !is_string($res)) {
        return false;
    }

    $json = json_decode($res, true);
    return is_array($json) && ($json['status'] ?? '') === 'ok';
}

/**
 * Normalizes Devanagari numerals to ASCII digits (0-9).
 */
function normalize_digits(string $str): string {
    $devanagari = ['०','१','२','३','४','५','६','७','८','९'];
    $ascii = ['0','1','2','3','4','5','6','7','8','9'];
    return str_replace($devanagari, $ascii, $str);
}

/**
 * Detects the query language based on Unicode script.
 * Devanagari -> 'hi' (Hindi)
 * Tamil -> 'ta' (Tamil)
 * Otherwise -> 'en' (English)
 */
function detect_query_script_language(string $text): string {
    if (preg_match('/[\x{0900}-\x{097F}]/u', $text)) {
        return 'hi';
    }
    if (preg_match('/[\x{0B80}-\x{0BFF}]/u', $text)) {
        return 'ta';
    }
    return 'en';
}

/**
 * Checks if the generated text is a recognized grounded refusal.
 */
function is_grounded_refusal(string $text): bool {
    $refusalPatterns = [
        'verified records do not contain',
        'information is not available',
        'information is not present',
        'records do not contain',
        'not available in the verified',
        'not mentioned in the verified',
        'does not contain information',
        'do not contain this information',
        'सत्यापित रिकॉर्ड',
        'जानकारी उपलब्ध नहीं',
        'रिकॉर्ड में नहीं',
        'जानकारी नहीं',
        'தகவல் இல்லை',
        'பதிவுகளில் இல்லை'
    ];
    foreach ($refusalPatterns as $rp) {
        if (mb_stripos($text, $rp, 0, 'UTF-8') !== false) {
            return true;
        }
    }
    return false;
}

/**
 * Post-generation validation for language/script adherence.
 */
function validate_language_script(string $generated, string $targetLang, ?string &$reason = null): bool {
    $trimmed = trim($generated);
    if ($trimmed === '') {
        $reason = "Empty output";
        return false;
    }

    if (is_grounded_refusal($trimmed)) {
        return true;
    }

    if ($targetLang === 'hi') {
        // Must contain Devanagari characters
        if (!preg_match('/[\x{0900}-\x{097F}]/u', $trimmed)) {
            $reason = "Expected Hindi in Devanagari script, but no Devanagari characters found";
            return false;
        }
        // Must NOT contain Tamil characters
        if (preg_match('/[\x{0B80}-\x{0BFF}]/u', $trimmed)) {
            $reason = "Expected Hindi in Devanagari script, but Tamil characters were found in output";
            return false;
        }
        return true;
    }

    if ($targetLang === 'ta') {
        // Must contain Tamil characters
        if (!preg_match('/[\x{0B80}-\x{0BFF}]/u', $trimmed)) {
            $reason = "Expected Tamil script, but no Tamil characters found";
            return false;
        }
        // Must NOT contain Devanagari characters
        if (preg_match('/[\x{0900}-\x{097F}]/u', $trimmed)) {
            $reason = "Expected Tamil script, but Devanagari characters were found in output";
            return false;
        }
        return true;
    }

    // Default: English ('en')
    if (preg_match('/[\x{0B80}-\x{0BFF}\x{0900}-\x{097F}]/u', $trimmed)) {
        $reason = "Expected English, but Indic script characters were found in output";
        return false;
    }

    return true;
}

/**
 * Post-generation validation for strict grounding.
 *
 * Checks:
 * 1. Grounded Refusal check: If model states facts do not contain the answer, accept.
 * 2. Word count check: Max 40 words.
 * 3. Numerical & time token check: Every number/time in generated text MUST exist in verified facts.
 * 4. Unsolicited advice/keywords check: Forbidden advice patterns (belongings, secure, caution, etc.)
 *    cannot appear in generated text unless they exist in verified facts.
 *
 * @param string      $generated       Raw output from Gemma
 * @param string      $verifiedFact    Ground truth fact block supplied to Gemma
 * @param string      $question        User's question
 * @param string|null &$rejectionReason Set to description if rejected
 * @return bool True if valid grounded answer, false if rejected
 */
function validate_grounded_answer(
    string $generated,
    string $verifiedFact,
    string $question,
    ?string &$rejectionReason = null
): bool {
    $trimmed = trim($generated);
    if ($trimmed === '') {
        $rejectionReason = "Empty output";
        return false;
    }

    // 1. Refusal check: Standard grounded refusal
    if (is_grounded_refusal($trimmed)) {
        return true;
    }

    // 2. Length check: Max 40 words
    $words = preg_split('/\s+/u', $trimmed, -1, PREG_SPLIT_NO_EMPTY);
    if (count($words) > 40) {
        $rejectionReason = "Word count exceeded 40 words (" . count($words) . " words)";
        return false;
    }

    // 3. Numerical & time token check
    // Extract numbers, times, and numeric tokens from generated output
    preg_match_all('/\b\d+(?::\d+)?\b/u', $trimmed, $genMatches);
    $genNumbers = array_unique($genMatches[0] ?? []);

    preg_match_all('/\b\d+(?::\d+)?\b/u', $verifiedFact, $factMatches);
    $factNumbers = array_unique($factMatches[0] ?? []);

    $factNumbersNorm = [];
    foreach ($factNumbers as $fn) {
        $fnNorm = normalize_digits($fn);
        $factNumbersNorm[$fnNorm] = true;
        if (strpos($fnNorm, ':') !== false) {
            $parts = explode(':', $fnNorm);
            $h = (int)$parts[0];
            $m = (int)$parts[1];
            $factNumbersNorm[$h . ':' . sprintf('%02d', $m)] = true;
            $factNumbersNorm[sprintf('%02d:%02d', $h, $m)] = true;
        }
    }

    foreach ($genNumbers as $gn) {
        $gnNorm = normalize_digits($gn);
        $isGrounded = isset($factNumbersNorm[$gnNorm]);
        if (!$isGrounded && strpos($gnNorm, ':') !== false) {
            $parts = explode(':', $gnNorm);
            $h = (int)$parts[0];
            $m = (int)$parts[1];
            if (isset($factNumbersNorm[$h . ':' . sprintf('%02d', $m)]) || isset($factNumbersNorm[sprintf('%02d:%02d', $h, $m)])) {
                $isGrounded = true;
            }
        }
        if (!$isGrounded) {
            $rejectionReason = "Ungrounded numerical token '$gn' not found in verified facts";
            return false;
        }
    }

    // 4. Unsolicited advice/keywords check
    $forbiddenAdvicePatterns = [
        'belongings',
        'valuables',
        'secure',
        'security rules',
        'caution',
        'careful',
        'keep in mind',
        'ensure you',
        'beware',
        'watch out',
        'stay alert',
        'alert',
        'pickpocket',
        'dark alleys',
        'safeguard'
    ];

    foreach ($forbiddenAdvicePatterns as $adv) {
        if (preg_match('/\b' . preg_quote($adv, '/') . '\b/i', $trimmed)) {
            if (!preg_match('/\b' . preg_quote($adv, '/') . '\b/i', $verifiedFact)) {
                $rejectionReason = "Unsolicited advice pattern '$adv' found in output but not in verified facts";
                return false;
            }
        }
    }

    return true;
}

/**
 * Returns the rejection reason of the most recent call_local_gemma validation, if any.
 */
function get_local_ai_last_rejection_reason(): ?string {
    return $GLOBALS['LOCAL_AI_LAST_REJECTION_REASON'] ?? null;
}

/**
 * Constructs the system instruction with explicit target language rules.
 */
function build_gemma_system_instruction(string $targetLang, bool $isRetry = false): string {
    $langInstruction = "";
    if ($targetLang === 'hi') {
        $langInstruction = "9. LANGUAGE REQUIREMENT: The user's question is in Hindi. You MUST respond ONLY in Hindi. Use Devanagari script. Do not respond in Tamil. Do not respond in English. Do not translate the answer into another language. Use only the supplied Verified Facts.";
        if ($isRetry) {
            $langInstruction .= " CRITICAL RETRY: Your previous attempt used the wrong language/script. You MUST write in Hindi (Devanagari script) ONLY. DO NOT use Tamil script under any circumstances.";
        }
    } elseif ($targetLang === 'ta') {
        $langInstruction = "9. LANGUAGE REQUIREMENT: The user's question is in Tamil. You MUST respond ONLY in Tamil script. Do not respond in Hindi. Do not respond in English. Use only the supplied Verified Facts.";
        if ($isRetry) {
            $langInstruction .= " CRITICAL RETRY: You MUST write ONLY in Tamil script. DO NOT use Hindi or English.";
        }
    } else {
        $langInstruction = "9. LANGUAGE REQUIREMENT: The user's question is in English. You MUST respond ONLY in English. Do not respond in Tamil or Hindi. Use only the supplied Verified Facts.";
    }

    return "You are Semmozhi, a virtual travel assistant for Tamil Nadu.\n"
        . "CRITICAL INSTRUCTIONS:\n"
        . "1. You have NO external, general, or world knowledge. You know ONLY the text in 'Verified Facts'.\n"
        . "2. Answer the user's question using ONLY the provided Verified Facts.\n"
        . "3. Never assume, extrapolate, speculate, or add general travel advice, tips, or pleasantries.\n"
        . "4. Never add safety warnings, caution statements, or advice unless explicitly written in the Verified Facts.\n"
        . "5. If the Verified Facts answer the question, state the answer concisely in 1 sentence. Never append any disclaimer or refusal text when you have answered.\n"
        . "6. If the Verified Facts do NOT contain the answer (such as hours for an unlisted church or building), reply ONLY: 'The verified records do not contain this information.'\n"
        . "7. If the user asks whether a fee, timing, or fact is a specific value, state the actual verified fact. Never confirm unverified values.\n"
        . "8. Keep the answer extremely concise (1-2 sentences, maximum 25 words).\n"
        . $langInstruction . "\n"
        . "10. Do not provide emergency instructions yourself.\n"
        . "11. CONVERSATION CONTEXT RULE: If a 'CONVERSATION CONTEXT' section is provided, use it ONLY to understand what the user's current question refers to (such as 'there', 'it', 'that place', or follow-up questions). Do NOT treat previous Semmozhi/assistant messages as authoritative factual data. Only 'Verified Facts' is authoritative. Never invent missing travel facts from conversation history.";
}

/**
 * Builds the structured user-message prompt for Gemma, separating optional
 * validated conversation context from Verified Facts and CURRENT USER QUESTION.
 */
function build_gemma_user_content(
    string $topic,
    string $verifiedFact,
    string $question,
    string $targetLangLabel,
    array $conversationHistory = []
): string {
    $sections = [];

    if (!empty($conversationHistory)) {
        $contextLines = [];
        $bounded = array_slice($conversationHistory, -6);
        foreach ($bounded as $entry) {
            if (!is_array($entry)) {
                continue;
            }
            $role = $entry['role'] ?? null;
            $text = $entry['text'] ?? null;
            if (!is_string($role) || !is_string($text)) {
                continue;
            }
            $cleanText = trim($text);
            if ($cleanText === '') {
                continue;
            }
            if (mb_strlen($cleanText, 'UTF-8') > 500) {
                $cleanText = mb_substr($cleanText, 0, 500, 'UTF-8');
            }
            if ($role === 'user') {
                $contextLines[] = "User: " . $cleanText;
            } elseif ($role === 'assistant') {
                $contextLines[] = "Semmozhi: " . $cleanText;
            }
        }
        if (!empty($contextLines)) {
            $sections[] = "CONVERSATION CONTEXT:\n" . implode("\n", $contextLines) . "\nEND CONVERSATION CONTEXT";
        }
    }

    $sections[] = "Topic / Landmark:\n" . trim($topic);
    $sections[] = "Verified Facts:\n" . trim($verifiedFact);
    $sections[] = "CURRENT USER QUESTION:\n" . trim($question);
    $sections[] = "Required Response Language: " . $targetLangLabel;

    return implode("\n\n", $sections);
}

/**
 * Executes a single HTTP POST request to the local llama-server.
 */
function execute_gemma_call(
    string $systemInstruction,
    string $userContent,
    int $maxTokens,
    float $temperature,
    int $timeout
): ?string {
    $payload = [
        "messages" => [
            [
                "role" => "system",
                "content" => $systemInstruction
            ],
            [
                "role" => "user",
                "content" => $userContent
            ]
        ],
        "temperature" => $temperature,
        "max_tokens" => $maxTokens,
        "stream" => false
    ];

    $jsonPayload = json_encode($payload, JSON_UNESCAPED_UNICODE);
    if ($jsonPayload === false) {
        return null;
    }

    $ch = curl_init(LOCAL_AI_ENDPOINT);
    if ($ch === false) {
        return null;
    }

    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, $jsonPayload);
    curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, LOCAL_AI_CONNECT_TIMEOUT_SEC);
    curl_setopt($ch, CURLOPT_TIMEOUT, $timeout);
    curl_setopt($ch, CURLOPT_HTTPHEADER, [
        'Content-Type: application/json; charset=utf-8'
    ]);

    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlErr = curl_error($ch);
    curl_close($ch);

    if ($response === false || $httpCode !== 200 || !empty($curlErr)) {
        return null;
    }

    $resData = json_decode($response, true);
    if (!is_array($resData)) {
        return null;
    }

    $content = $resData['choices'][0]['message']['content'] ?? null;
    if (!is_string($content)) {
        return null;
    }

    $trimmed = trim($content);
    return ($trimmed !== '') ? $trimmed : null;
}

/**
 * Calls local Gemma 3 4B via llama-server with verified facts, user's question,
 * and explicit language detection and post-generation script validation.
 *
 * @param string $question     Traveler's question
 * @param string $topic        Retrieved topic / landmark name
 * @param string $verifiedFact Ground truth verified fact block
 * @param array  $options      Optional overrides (e.g. max_tokens, temperature)
 * @return string|null Natural language synthesized answer, or null on any failure/timeout/rejection
 */
function call_local_gemma(
    string $question,
    string $topic,
    string $verifiedFact,
    array $options = [],
    array $conversationHistory = []
): ?string {
    $GLOBALS['LOCAL_AI_LAST_REJECTION_REASON'] = null;

    $cleanQuestion = trim($question);
    $cleanFact = trim($verifiedFact);

    if ($cleanQuestion === '' || $cleanFact === '') {
        return null;
    }

    $targetLang = $options['target_lang'] ?? detect_query_script_language($cleanQuestion);
    $maxTokens = (int)($options['max_tokens'] ?? LOCAL_AI_MAX_TOKENS);
    $temperature = (float)($options['temperature'] ?? LOCAL_AI_TEMPERATURE);
    $timeout = (int)($options['timeout'] ?? LOCAL_AI_TIMEOUT_SEC);

    $targetLangLabel = ($targetLang === 'hi') ? "Hindi (Devanagari script)" : (($targetLang === 'ta') ? "Tamil script" : "English");
    $userContent = build_gemma_user_content(
        $topic,
        $cleanFact,
        $cleanQuestion,
        $targetLangLabel,
        $conversationHistory
    );

    // Attempt 1
    $systemInstruction = build_gemma_system_instruction($targetLang, false);
    $output = execute_gemma_call($systemInstruction, $userContent, $maxTokens, $temperature, $timeout);

    // Validate Attempt 1
    $rejectionReason = null;
    $langReason = null;
    $validGrounding = ($output !== null) && validate_grounded_answer($output, $cleanFact, $cleanQuestion, $rejectionReason);
    $validLanguage = ($output !== null) && validate_language_script($output, $targetLang, $langReason);

    // If grounding passed but language failed, retry ONCE with strengthened retry instruction
    if ($output !== null && $validGrounding && !$validLanguage) {
        error_log("[local_ai] Language validation failed on attempt 1: {$langReason}. Retrying once with strengthened prompt...");
        $retryInstruction = build_gemma_system_instruction($targetLang, true);
        $retryOutput = execute_gemma_call($retryInstruction, $userContent, $maxTokens, $temperature, $timeout);

        if ($retryOutput !== null) {
            $retryGndReason = null;
            $retryLangReason = null;
            if (validate_grounded_answer($retryOutput, $cleanFact, $cleanQuestion, $retryGndReason) &&
                validate_language_script($retryOutput, $targetLang, $retryLangReason)) {
                return $retryOutput;
            }
        }
    }

    if ($output !== null && $validGrounding && $validLanguage) {
        return $output;
    }

    $finalReason = $rejectionReason ?? $langReason ?? "Empty or failed output";
    $GLOBALS['LOCAL_AI_LAST_REJECTION_REASON'] = $finalReason;
    error_log("[local_ai] Rejected output. Reason: {$finalReason}");
    return null;
}
