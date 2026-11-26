<?php
/**
 * Destination Aliases API Endpoint
 *
 * Provides access to verified destination aliases stored in `destination_aliases` table.
 * Supports:
 *   1. Listing all verified destination aliases with their canonical destination names and IDs.
 *   2. Resolving a user query (?q=...) to its canonical destination using verified aliases.
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../config.php';
$conn->set_charset('utf8mb4');

// Only handle HTTP request routing and method validation if invoked directly as endpoint
if (isset($_SERVER['SCRIPT_FILENAME']) && realpath($_SERVER['SCRIPT_FILENAME']) === realpath(__FILE__)) {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') {
        http_response_code(405);
        echo json_encode([
            "status" => "error",
            "message" => "Only GET requests are allowed"
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    try {
        $q = isset($_GET['q']) ? trim((string)$_GET['q']) : '';

        // Fetch all active aliases from the database (source of truth)
        $sql = "SELECT a.id, a.destination_id, d.name AS destination_name, a.alias, a.language
                FROM destination_aliases a
                JOIN destinations d ON a.destination_id = d.id
                WHERE d.is_active = 1
                ORDER BY CHAR_LENGTH(a.alias) DESC, a.id ASC";

        $result = $conn->query($sql);
        if (!$result) {
            throw new Exception("Failed to query destination aliases: " . $conn->error);
        }

        $aliases = [];
        while ($row = $result->fetch_assoc()) {
            $aliases[] = [
                "id" => (int)$row['id'],
                "destination_id" => (int)$row['destination_id'],
                "destination_name" => $row['destination_name'],
                "alias" => $row['alias'],
                "language" => $row['language']
            ];
        }

        // Branch 1: If query 'q' is provided, perform alias resolution
        if ($q !== '') {
            $match = resolveAliasMatch($q, $aliases, $conn);
            if ($match !== null) {
                echo json_encode([
                    "status" => "success",
                    "matched" => true,
                    "destination_id" => $match['destination_id'],
                    "canonical_name" => $match['canonical_name'],
                    "matched_alias" => $match['matched_alias'],
                    "language" => $match['language'],
                    "is_exact_match" => $match['is_exact_match']
                ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                exit;
            }

            echo json_encode([
                "status" => "success",
                "matched" => false,
                "message" => "No verified destination alias matched"
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }

        // Branch 2: Return list of all verified aliases
        echo json_encode([
            "status" => "success",
            "count" => count($aliases),
            "aliases" => $aliases
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

    } catch (Throwable $e) {
        http_response_code(500);
        echo json_encode([
            "status" => "error",
            "message" => "Unable to process destination aliases: " . $e->getMessage()
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

/**
 * Resolves a query to its canonical destination using verified aliases and canonical names.
 *
 * Implements:
 * 1. Safe Unicode normalization (NFC)
 * 2. Case-insensitivity for English
 * 3. Preservation of Tamil and Hindi scripts
 * 4. Word/token boundary matching
 * 5. Specificity preference: Exact alias match > longest alias match
 * 6. Ambiguity protection: If two distinct destinations tie in specificity, returns null
 * 7. Canonical destination name fallback check
 */
function safeNormalizeUnicode(string $str): string {
    if (class_exists('Normalizer') && method_exists('Normalizer', 'normalize')) {
        $norm = Normalizer::normalize($str, Normalizer::FORM_C);
        if ($norm !== false) return $norm;
    }
    return $str;
}

function resolveAliasMatch(string $rawQuery, array $aliases, mysqli $conn): ?array {
    $normQuery = trim(safeNormalizeUnicode($rawQuery));
    if ($normQuery === '') {
        return null;
    }

    $lowerQuery = mb_strtolower($normQuery, 'UTF-8');

    // 1. Check exact alias match
    $exactMatches = [];
    foreach ($aliases as $a) {
        $normAlias = trim(safeNormalizeUnicode($a['alias']));
        $lowerAlias = mb_strtolower($normAlias, 'UTF-8');

        if ($lowerQuery === $lowerAlias) {
            $exactMatches[] = [
                'destination_id' => $a['destination_id'],
                'canonical_name' => $a['destination_name'],
                'matched_alias' => $a['alias'],
                'language' => $a['language'],
                'is_exact_match' => true,
                'alias_length' => mb_strlen($normAlias, 'UTF-8')
            ];
        }
    }

    if (!empty($exactMatches)) {
        // If all exact matches point to the same destination, return it
        $firstDestId = $exactMatches[0]['destination_id'];
        $consistent = true;
        foreach ($exactMatches as $em) {
            if ($em['destination_id'] !== $firstDestId) {
                $consistent = false;
                break;
            }
        }
        if ($consistent) {
            return $exactMatches[0];
        }
        // Ambiguity among exact matches pointing to different destinations -> unresolved
        return null;
    }

    // 2. Check phrase / token-boundary match
    // Prefers the longest / highest-specificity alias
    $phraseMatches = [];
    foreach ($aliases as $a) {
        $normAlias = trim(safeNormalizeUnicode($a['alias']));
        $lowerAlias = mb_strtolower($normAlias, 'UTF-8');
        if ($lowerAlias === '') continue;

        $escaped = preg_quote($lowerAlias, '/');

        // Boundary-safe Tamil morphological suffix support (locative, dative, accusative, genitive)
        $isTamil = ($a['language'] === 'ta') || preg_match('/\p{Tamil}/u', $normAlias);
        if ($isTamil) {
            if (str_ends_with($lowerAlias, 'ம்')) {
                $stem = preg_replace('/ம்$/u', '', $lowerAlias);
                $escapedStem = preg_quote($stem, '/');
                $mSuffixes = '(?:த்தில்|த்துக்கு|த்தை|த்தின்|த்துல|த்தோடு|த்துடன்|த்தால்)';
                $aliasPattern = '(?:' . $escaped . '|' . $escapedStem . $mSuffixes . ')';
            } else {
                $directSuffixes = '(?:க்கு|வுக்கு|உக்கு|இல்|வில்|யில்|ல்|ஐ|வை|யை|இன்|வின்|யின்|ல)';
                $aliasPattern = $escaped . '(?:' . $directSuffixes . ')?';
            }
        } else {
            $aliasPattern = $escaped;
        }

        // Match at word/token boundary
        if (preg_match('/(?:\b|[\s\p{P}]|^)' . $aliasPattern . '(?:\b|[\s\p{P}]|$)/u', $lowerQuery)) {
            $phraseMatches[] = [
                'destination_id' => $a['destination_id'],
                'canonical_name' => $a['destination_name'],
                'matched_alias' => $a['alias'],
                'language' => $a['language'],
                'is_exact_match' => false,
                'alias_length' => mb_strlen($normAlias, 'UTF-8')
            ];
        }
    }

    if (!empty($phraseMatches)) {
        // Sort by alias_length DESC (longest/highest-specificity first)
        usort($phraseMatches, function($a, $b) {
            return $b['alias_length'] <=> $a['alias_length'];
        });

        // Check if top match has an ambiguous tie with a different destination
        $bestLen = $phraseMatches[0]['alias_length'];
        $topDestId = $phraseMatches[0]['destination_id'];

        foreach ($phraseMatches as $pm) {
            if ($pm['alias_length'] === $bestLen && $pm['destination_id'] !== $topDestId) {
                // Ambiguous tie at the same specificity level -> unresolved
                return null;
            }
        }

        return $phraseMatches[0];
    }

    // 3. Fallback: Check canonical destination names from destinations table
    $canonicalRes = $conn->query("SELECT id, name FROM destinations WHERE is_active = 1 ORDER BY CHAR_LENGTH(name) DESC");
    if ($canonicalRes) {
        while ($d = $canonicalRes->fetch_assoc()) {
            $normName = trim(safeNormalizeUnicode($d['name']));
            $lowerName = mb_strtolower($normName, 'UTF-8');
            $escaped = preg_quote($lowerName, '/');

            if ($lowerQuery === $lowerName || preg_match('/(?:\b|[\s\p{P}]|^)' . $escaped . '(?:\b|[\s\p{P}]|$)/u', $lowerQuery)) {
                return [
                    'destination_id' => (int)$d['id'],
                    'canonical_name' => $d['name'],
                    'matched_alias' => $d['name'],
                    'language' => 'en',
                    'is_exact_match' => ($lowerQuery === $lowerName)
                ];
            }
        }
    }

    return null;
}
