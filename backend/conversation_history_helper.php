<?php
declare(strict_types=1);

if (!defined('CONVERSATION_HISTORY_MAX_MESSAGES')) {
    define('CONVERSATION_HISTORY_MAX_MESSAGES', 6);
}
if (!defined('CONVERSATION_HISTORY_MAX_TEXT_CHARS')) {
    define('CONVERSATION_HISTORY_MAX_TEXT_CHARS', 500);
}

/**
 * Parses and validates optional `conversation_history` from an ask.php request.
 *
 * - Missing or non-array input returns [].
 * - Keeps only entries with role === 'user' or 'assistant' and non-empty string text.
 * - Bounds each message text to $maxTextChars UTF-8 characters.
 * - Preserves chronological order and keeps at most the newest $maxMessages entries.
 *
 * @param mixed $rawHistory
 * @return array<int, array{role: string, text: string}>
 */
function parse_and_validate_conversation_history(
    mixed $rawHistory,
    int $maxMessages = CONVERSATION_HISTORY_MAX_MESSAGES,
    int $maxTextChars = CONVERSATION_HISTORY_MAX_TEXT_CHARS
): array {
    if (!is_array($rawHistory) || $maxMessages <= 0 || $maxTextChars <= 0) {
        return [];
    }

    $validated = [];
    foreach ($rawHistory as $entry) {
        if (!is_array($entry)) {
            continue;
        }

        $role = $entry['role'] ?? null;
        if (!is_string($role) || ($role !== 'user' && $role !== 'assistant')) {
            continue;
        }

        $text = $entry['text'] ?? null;
        if (!is_string($text)) {
            continue;
        }

        $cleanText = trim($text);
        if ($cleanText === '') {
            continue;
        }

        if (mb_strlen($cleanText, 'UTF-8') > $maxTextChars) {
            $cleanText = mb_substr($cleanText, 0, $maxTextChars, 'UTF-8');
        }

        $validated[] = [
            'role' => $role,
            'text' => $cleanText
        ];
    }

    if (count($validated) > $maxMessages) {
        $validated = array_slice($validated, -$maxMessages);
    }

    return array_values($validated);
}
