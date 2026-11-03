package com.example.tourcompanion

import java.util.Locale

/**
 * Text-driven approximate viseme mapper for Semmozhi.
 * Maps grapheme patterns and punctuation pauses to 5 core visual mouth shapes.
 */
object VisemeMapper {

    enum class Viseme {
        REST,
        A,
        E,
        O,
        M
    }

    /**
     * Maps a spoken word/range to an approximate viseme:
     * - REST for punctuation, pauses, and whitespace.
     * - M for bilabial sounds (m, b, p) where lips meet.
     * - O for rounded vowels and labial-velar sounds (o, u, w).
     * - A for open vowel sounds (a, aa).
     * - E for spread front vowels (e, i, y).
     */
    fun map(word: String, fullText: String = "", start: Int = 0, end: Int = word.length): Viseme {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return Viseme.REST

        // Punctuation check
        if (trimmed.all { it in ".,!?;: \t\n\r-" }) {
            return Viseme.REST
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Bilabials: lips close together (m, b, p)
        if (lower.any { it == 'm' || it == 'b' || it == 'p' }) {
            return Viseme.M
        }

        // 2. Rounded: o, u, w
        if (lower.any { it == 'o' || it == 'u' || it == 'w' }) {
            return Viseme.O
        }

        // 3. Open: a, aa
        if (lower.contains("aa") || lower.contains('a')) {
            return Viseme.A
        }

        // 4. Spread: e, i, y
        if (lower.any { it == 'e' || it == 'i' || it == 'y' }) {
            return Viseme.E
        }

        // Trailing punctuation relax
        val trailingChar = if (fullText.isNotEmpty() && end < fullText.length) fullText[end] else ' '
        if (trailingChar in ".,!?;:") {
            return Viseme.REST
        }

        return Viseme.REST
    }
}

