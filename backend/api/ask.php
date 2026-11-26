<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';

// Include local AI helper (Phase 2)
if (file_exists(__DIR__ . '/../local_ai.php')) {
    require_once __DIR__ . '/../local_ai.php';
} elseif (file_exists(__DIR__ . '/local_ai.php')) {
    require_once __DIR__ . '/local_ai.php';
}

// Include conversation history validation helper (Step 3B)
if (file_exists(__DIR__ . '/../conversation_history_helper.php')) {
    require_once __DIR__ . '/../conversation_history_helper.php';
} elseif (file_exists(__DIR__ . '/conversation_history_helper.php')) {
    require_once __DIR__ . '/conversation_history_helper.php';
}

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
$userLat = isset($input['latitude']) ? (float)$input['latitude'] : null;
$userLon = isset($input['longitude']) ? (float)$input['longitude'] : null;
$conversationHistory = function_exists('parse_and_validate_conversation_history')
    ? parse_and_validate_conversation_history($input['conversation_history'] ?? null)
    : [];

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
// 1. Emergency / Distress Preemption (100% Deterministic)
// -------------------------------------------------------------
$distressPhrases = [
    'emergency', 'sos', '112', 'save me', 'following me', 'followed me', 'being followed',
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

if (!$isEmergency && count($qTokens) <= 4) {
    $urgentWords = ['help', 'உதவி', 'மதद', 'मदद', 'emergency'];
    foreach ($urgentWords as $uw) {
        if (in_array($uw, $qTokens, true)) {
            $isEmergency = true;
            break;
        }
    }
}

if ($isEmergency) {
    $emergencyAnswer = "EMERGENCY: Call 112 immediately for Police, Fire, or Ambulance services. For women's safety helpline call 1091. Move immediately to a well-lit, crowded area.";

    $emRes = $conn->query("SELECT answer FROM travel_tips WHERE LOWER(topic) = 'emergency' LIMIT 1");
    if ($emRes && $row = $emRes->fetch_assoc()) {
        $emergencyAnswer = $row['answer'];
    }

    $stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
    $stmt->bind_param("iss", $userId, $question, $emergencyAnswer);
    $stmt->execute();
    $stmt->close();

    echo json_encode([
        "status" => "ok",
        "question" => $question,
        "topic" => "Emergency",
        "answer" => $emergencyAnswer,
        "ai_generated" => false
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// -------------------------------------------------------------
// 2. Retrieval: Multi-Path Verified Fact Lookup
// -------------------------------------------------------------

$matchedTopic = null;
$verifiedFacts = null;
$deterministicAnswer = null;

// Multilingual synonym concepts to map Tamil/Hindi query terms to database keywords
$multilingualSynonyms = [
    // Tamil
    'இரவு' => ['night', 'dark'],
    'பாதுகாப்பு' => ['safe', 'safety'],
    'பணம்' => ['money', 'cash'],
    'கட்டணம்' => ['fee', 'ticket'],
    'மருத்துவம்' => ['health', 'doctor', 'medicine'],
    'மருந்து' => ['medicine', 'pharmacy'],
    'உணவு' => ['food', 'restaurant', 'eat'],
    'தண்ணீர்' => ['water', 'drink'],
    'பேருந்து' => ['bus', 'transport'],
    'ரயில்' => ['train', 'railway'],
    'கோவில்' => ['temple'],
    'அருங்காட்சியகம்' => ['museum'],
    // Hindi
    'रात' => ['night', 'dark'],
    'सुरक्षा' => ['safe', 'safety'],
    'पैसे' => ['money', 'cash'],
    'शुल्क' => ['fee', 'ticket'],
    'दवा' => ['health', 'medicine'],
    'डॉक्टर' => ['doctor', 'health'],
    'खाना' => ['food', 'restaurant', 'eat'],
    'पानी' => ['water', 'drink'],
    'बस' => ['bus', 'transport'],
    'ट्रेन' => ['train', 'railway'],
    'मंदिर' => ['temple'],
    'संग्रहालय' => ['museum']
];

$stopWords = [
    "where", "what", "when", "why", "how", "can", "could", "should", "would",
    "is", "are", "the", "a", "an", "i", "me", "my", "you", "your", "do",
    "does", "did", "please", "tell", "to", "of", "in", "for", "on", "at",
    "there", "this", "that", "it", "and", "or", "with", "be", "about",
    "near", "any", "some", "get", "go", "from", "we", "us", "have", "has",
    "will", "am", "was", "were", "so", "if", "not", "no"
];

$contentTokens = array_values(array_filter($qTokens, fn($t) => !in_array($t, $stopWords, true)));

// Expand content tokens with concept synonyms
foreach ($qTokens as $qt) {
    if (isset($multilingualSynonyms[$qt])) {
        foreach ($multilingualSynonyms[$qt] as $syn) {
            $contentTokens[] = $syn;
        }
    }
}
$contentTokens = array_values(array_unique($contentTokens));

// -------------------------------------------------------------
// Path A: Verified Destinations (destinations & destination_aliases)
// -------------------------------------------------------------
require_once __DIR__ . '/destination-aliases.php';

$matchedDestId = null;

// 1. Check verified destination aliases from database (source of truth)
$aliasSql = "SELECT a.destination_id, d.name AS destination_name, a.alias, a.language
             FROM destination_aliases a
             JOIN destinations d ON a.destination_id = d.id
             WHERE d.is_active = 1
             ORDER BY CHAR_LENGTH(a.alias) DESC";
$aliasRes = $conn->query($aliasSql);
if ($aliasRes) {
    $dbAliases = [];
    while ($arow = $aliasRes->fetch_assoc()) {
        $dbAliases[] = $arow;
    }
    $aliasMatch = resolveAliasMatch($question, $dbAliases, $conn);
    if ($aliasMatch !== null) {
        $matchedDestId = $aliasMatch['destination_id'];
    }
}

// 2. Fallback to existing destination pattern matching if no alias matched
if ($matchedDestId === null) {
    $destPatterns = [
        ['id' => 1, 'keys' => ['marina beach', 'marina', 'மெரினா', 'மரினா', 'मरीना बीच', 'मरीना']],
        ['id' => 2, 'keys' => ['kapaleeshwarar', 'kapaleeswarar', 'kapaleeswar', 'mylapore temple', 'கபாலீஸ்வரர்', 'कपालेश्वर', 'कपालीश्वर']],
        ['id' => 3, 'keys' => ['fort st george', 'fort st. george', 'st mary', 'st. mary', 'புனித ஜார்ஜ்', 'फोर्ट सेंट जॉर्ज']],
        ['id' => 4, 'keys' => ['government museum', 'egmore museum', 'national art gallery', 'அருங்காட்சியகம்', 'எழும்பூர் அருங்காட்சியகம்', 'एग्मोर संग्रहालय', 'संग्रहालय']],
        ['id' => 5, 'keys' => ['besant nagar beach', 'besant nagar', 'elliot', 'elliots', 'edward elliot', 'பெசன்ட் நகர்', 'इलियट्स बीच', 'इलियट्स']]
    ];

    foreach ($destPatterns as $dp) {
        foreach ($dp['keys'] as $k) {
            if (mb_strpos($cleanQ, $k, 0, 'UTF-8') !== false) {
                $matchedDestId = $dp['id'];
                break 2;
            }
        }
    }
}

// 3. Conversational context destination resolution for anaphora ("there", "it", "that place")
if ($matchedDestId === null && !empty($conversationHistory)) {
    $hasAnaphora = (bool)preg_match('/\b(?:there|it|that\s+place|this\s+place|that\s+destination|this\s+destination)\b/i', $question);
    if ($hasAnaphora) {
        $reversedHistory = array_reverse($conversationHistory);
        foreach ($reversedHistory as $hEntry) {
            $hText = $hEntry['text'] ?? '';
            if ($hText === '') continue;
            if (!empty($dbAliases)) {
                $hAliasMatch = resolveAliasMatch($hText, $dbAliases, $conn);
                if ($hAliasMatch !== null) {
                    $matchedDestId = $hAliasMatch['destination_id'];
                    break;
                }
            }
            $hClean = normalize_text($hText);
            foreach ($destPatterns as $dp) {
                foreach ($dp['keys'] as $k) {
                    if (mb_strpos($hClean, $k, 0, 'UTF-8') !== false) {
                        $matchedDestId = $dp['id'];
                        break 3;
                    }
                }
            }
        }
    }
}

if ($matchedDestId !== null) {
    $stmt = $conn->prepare("SELECT id, name, city, category, overview, timings, entry_fee, how_to_reach, best_time_to_visit, safety_tips, things_to_do FROM destinations WHERE id = ? AND is_active = 1");
    if ($stmt) {
        $stmt->bind_param("i", $matchedDestId);
        $stmt->execute();
        $destRow = $stmt->get_result()->fetch_assoc();
        $stmt->close();

        if ($destRow) {
            $destDisplayName = ($destRow['id'] == 3) ? 'Fort St. George' : $destRow['name'];
            $matchedTopic = $destDisplayName;

            $isTimingQuery = (
                mb_strpos($cleanQ, 'timing', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'time', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'hour', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'open', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'close', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'when', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'நேரம்', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'समय', 0, 'UTF-8') !== false
            );

            $isFeeQuery = (
                mb_strpos($cleanQ, 'fee', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'ticket', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'cost', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'price', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'rate', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'charge', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'கட்டணம்', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'शुल्क', 0, 'UTF-8') !== false
            );

            $isReachQuery = (
                mb_strpos($cleanQ, 'reach', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'how to get', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'how to go', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'route', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'direction', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'போகலாம்', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'जा सकते', 0, 'UTF-8') !== false
            );

            $isSafetyQuery = (
                mb_strpos($cleanQ, 'safe', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'safety', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'night', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'danger', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'பாதுகாப்பு', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'सुरक्षा', 0, 'UTF-8') !== false
            );

            $isActivityQuery = (
                mb_strpos($cleanQ, 'what can i do', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'things to do', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'what to do', 0, 'UTF-8') !== false ||
                mb_strpos($cleanQ, 'activities', 0, 'UTF-8') !== false
            );

            // Compact verified context construction
            if ($isTimingQuery) {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "Timings: " . $destRow['timings'];
                $deterministicAnswer = $destDisplayName . " timings: " . $destRow['timings'];
            } elseif ($isFeeQuery) {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "Entry Fee: " . $destRow['entry_fee'];
                $deterministicAnswer = $destDisplayName . " entry fee: " . $destRow['entry_fee'];
            } elseif ($isReachQuery) {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "How to Reach: " . $destRow['how_to_reach'];
                $deterministicAnswer = $destDisplayName . " how to reach: " . $destRow['how_to_reach'];
            } elseif ($isSafetyQuery) {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "Safety Tips: " . $destRow['safety_tips'];
                $deterministicAnswer = $destDisplayName . " safety tips: " . $destRow['safety_tips'];
            } elseif ($isActivityQuery && !empty($destRow['things_to_do'])) {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "Things to Do: " . $destRow['things_to_do'];
                $deterministicAnswer = "Things to do at " . $destDisplayName . ": " . $destRow['things_to_do'];
            } else {
                $verifiedFacts = "Destination: " . $destDisplayName . "\n"
                    . "Overview: " . $destRow['overview'] . "\n"
                    . "Timings: " . $destRow['timings'] . "\n"
                    . "Entry Fee: " . $destRow['entry_fee'];
                $deterministicAnswer = $destDisplayName . ": " . $destRow['overview'] . " Timings: " . $destRow['timings'] . " Entry fee: " . $destRow['entry_fee'];
            }
        }
    }
}

// -------------------------------------------------------------
// Path B: Verified Transport & MTC Scheduled Timing
// -------------------------------------------------------------
if ($matchedTopic === null) {
    $hasTimingWord = (
        mb_strpos($cleanQ, 'time', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'timing', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'schedule', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'when is', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'departure', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'depart', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'leave', 0, 'UTF-8') !== false ||
        mb_strpos($cleanQ, 'bus', 0, 'UTF-8') !== false
    );

    if ($hasTimingWord && preg_match('/\b([0-9]{1,4}[A-Za-z]{0,3}|[A-Za-z]{1,3}[0-9]{1,4}[A-Za-z]?)\b/', $question, $rm)) {
        $candidateRoute = strtoupper(trim($rm[1]));
        if (!in_array($candidateRoute, ['112', '1091', '100', '108', '101', '24', '7'])) {
            $extractedFrom = '';
            $extractedTo = '';
            if (preg_match('/\bfrom\s+([^?]+?)\s+to\s+([^?]+)/i', $question, $ftm)) {
                $extractedFrom = trim($ftm[1]);
                $extractedTo = trim($ftm[2]);
            } elseif (preg_match('/\bfrom\s+([^?]+)/i', $question, $fm)) {
                $extractedFrom = trim($fm[1]);
            }
            $extractedFrom = trim(preg_replace('/\b(?:bus|route)\b/i', '', $extractedFrom));
            $extractedTo = trim(preg_replace('/\b(?:bus|route)\b/i', '', $extractedTo));

            // Query local mtc-timing endpoint
            $mtcUrl = "http://127.0.0.1/tourcompanion/api/mtc-timing.php?route=" . urlencode($candidateRoute)
                . ($extractedFrom !== '' ? "&from=" . urlencode($extractedFrom) : "")
                . ($extractedTo !== '' ? "&to=" . urlencode($extractedTo) : "");

            $mtcCh = curl_init($mtcUrl);
            curl_setopt($mtcCh, CURLOPT_RETURNTRANSFER, true);
            curl_setopt($mtcCh, CURLOPT_TIMEOUT, 12);
            curl_setopt($mtcCh, CURLOPT_CONNECTTIMEOUT, 3);
            $mtcRes = curl_exec($mtcCh);
            $mtcCode = curl_getinfo($mtcCh, CURLINFO_HTTP_CODE);
            curl_close($mtcCh);

            if ($mtcCode === 200 && is_string($mtcRes)) {
                $mtcJson = json_decode($mtcRes, true);
                if (is_array($mtcJson)) {
                    if (($mtcJson['status'] ?? '') === 'multiple_origins') {
                        // Return clarification deterministically - do NOT call Gemma
                        $stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
                        $stmt->bind_param("iss", $userId, $question, $mtcJson['message']);
                        $stmt->execute();
                        $stmt->close();

                        echo json_encode([
                            "status" => "ok",
                            "question" => $question,
                            "topic" => "Route $candidateRoute",
                            "answer" => $mtcJson['message'],
                            "ai_generated" => false
                        ], JSON_UNESCAPED_UNICODE);
                        exit;
                    } elseif (($mtcJson['status'] ?? '') === 'success' && !empty($mtcJson['timings'])) {
                        $matchedTopic = "MTC Route $candidateRoute";
                        $timesStr = implode(', ', $mtcJson['timings']);
                        $deterministicAnswer = "Route $candidateRoute from " . $mtcJson['origin'] . " to " . $mtcJson['destination']
                            . " has scheduled departures at $timesStr.\n\nThese are scheduled MTC departure timings retrieved from the MTC service and may change in actual operation.";

                        // Pure tabular/schedule data: return deterministic answer directly, bypassing Gemma to eliminate timing hallucinations
                        $stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
                        $stmt->bind_param("iss", $userId, $question, $deterministicAnswer);
                        $stmt->execute();
                        $stmt->close();

                        echo json_encode([
                            "status" => "ok",
                            "question" => $question,
                            "topic" => $matchedTopic,
                            "answer" => $deterministicAnswer,
                            "ai_generated" => false
                        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                        exit;
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Path C: Verified Travel Tips (travel_tips table)
// -------------------------------------------------------------
if ($matchedTopic === null) {
    // Prevent weak generic travel-tip keywords (e.g. "evening", "beach", "visit", "plan")
    // from hijacking destination-discovery or activity-planning queries that lack a safety/tip signal.
    $placeOrActivitySignals = [
        'where can i go', 'where should i go', 'where can i visit',
        'places to visit', 'places to see', 'places nearby',
        'what can i do', 'things to do', 'want to visit',
        'attractions', 'explore a', 'visit a'
    ];
    $safetyOrTipSignals = [
        'safe', 'safety', 'unsafe', 'danger', 'dangerous',
        'tip', 'tips', 'advice', 'avoid', 'swim', 'swimming',
        'lifeguard', 'alone', 'solo', 'scam', 'theft', 'pickpocket'
    ];

    $hasPlaceOrActivityIntent = false;
    foreach ($placeOrActivitySignals as $sig) {
        if (mb_strpos($cleanQ, $sig, 0, 'UTF-8') !== false) {
            $hasPlaceOrActivityIntent = true;
            break;
        }
    }

    $hasSafetyOrTipSignal = false;
    foreach ($safetyOrTipSignals as $sig) {
        if (preg_match('/\b' . preg_quote($sig, '/') . '\b/u', $cleanQ)) {
            $hasSafetyOrTipSignal = true;
            break;
        }
    }

    if ($hasPlaceOrActivityIntent && !$hasSafetyOrTipSignal) {
        $clarificationAnswer = preg_match('/\bbeach(?:es)?\b/i', $cleanQ)
            ? "I have verified details for specific Chennai beaches such as Marina Beach and Besant Nagar Beach. Which beach would you like to ask about?"
            : "I don't have enough verified destination information for that request yet. Try asking about a specific Chennai destination like Marina Beach, Kapaleeshwarar Temple, Fort St. George, Government Museum, Besant Nagar Beach, or Valluvar Kottam.";

        $stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
        $stmt->bind_param("iss", $userId, $question, $clarificationAnswer);
        $stmt->execute();
        $stmt->close();

        echo json_encode([
            "status" => "ok",
            "question" => $question,
            "answer" => $clarificationAnswer,
            "ai_generated" => false
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $sql = "SELECT id, topic, keywords, answer FROM travel_tips";
    $result = $conn->query($sql);

    $bestTip = null;
    $bestScore = 0;
    $weakWords = ['help', 'find', 'place', 'safe', 'person', 'public', 'plan', 'visit', 'travel', 'trip', 'mall', 'park', 'street', 'route', 'road'];

    if ($result) {
        while ($row = $result->fetch_assoc()) {
            $topic = $row['topic'];
            $normTopic = normalize_text($topic);
            $normTopicPadded = ' ' . $normTopic . ' ';

            $keywords = array_map('trim', explode(',', $row['keywords']));
            $score = 0;

            foreach ($keywords as $kw) {
                $normKw = normalize_text($kw);
                if ($normKw === '') continue;

                $kwTokens = array_values(array_filter(explode(' ', $normKw), fn($t) => $t !== ''));
                $kwTokenCount = count($kwTokens);

                if ($kwTokenCount > 1) {
                    if (strpos($paddedQ, ' ' . $normKw . ' ') !== false) {
                        $score += 150 * $kwTokenCount;
                    }
                } else {
                    if (in_array($normKw, $contentTokens, true)) {
                        $score += in_array($normKw, $weakWords, true) ? 20 : 70;
                    } else {
                        foreach ($contentTokens as $ct) {
                            if (mb_strlen($ct, 'UTF-8') > 3 && rtrim($ct, 's') === rtrim($normKw, 's')) {
                                $score += in_array($normKw, $weakWords, true) ? 20 : 50;
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
    if ($bestTip !== null && $bestScore >= $MIN_SCORE) {
        $matchedTopic = $bestTip['topic'];
        $verifiedFacts = $bestTip['answer'];
        $deterministicAnswer = $bestTip['answer'];
    }
}

// -------------------------------------------------------------
// 3. No Verified Information Guard
// -------------------------------------------------------------
if ($matchedTopic === null || $verifiedFacts === null || $deterministicAnswer === null) {
    // If no verified facts matched, NEVER call Gemma (prevent hallucination)
    $unavailableAnswer = "Sorry, I don't have a specific travel tip for that question yet.";

    $stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
    $stmt->bind_param("iss", $userId, $question, $unavailableAnswer);
    $stmt->execute();
    $stmt->close();

    echo json_encode([
        "status" => "ok",
        "question" => $question,
        "answer" => $unavailableAnswer,
        "ai_generated" => false
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// -------------------------------------------------------------
// 4. Grounded Natural Synthesis with Local Gemma
// -------------------------------------------------------------
$finalAnswer = $deterministicAnswer;
$isAi = false;

if (function_exists('call_local_gemma')) {
    $aiAnswer = call_local_gemma($question, $matchedTopic, $verifiedFacts, [], $conversationHistory);
    if ($aiAnswer !== null && $aiAnswer !== '') {
        $finalAnswer = $aiAnswer;
        $isAi = true;
    }
}

// -------------------------------------------------------------
// 5. Query Logging & Standardized Android Response Contract
// -------------------------------------------------------------
$stmt = $conn->prepare("INSERT INTO query_logs (user_id, question, answer) VALUES (NULLIF(?, 0), ?, ?)");
$stmt->bind_param("iss", $userId, $question, $finalAnswer);
$stmt->execute();
$stmt->close();

$response = [
    "status" => "ok",
    "question" => $question,
    "topic" => $matchedTopic,
    "answer" => $finalAnswer,
    "ai_generated" => $isAi
];

echo json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
