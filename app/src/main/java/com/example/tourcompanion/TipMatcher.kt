package com.example.tourcompanion

import java.util.Locale
import kotlin.math.ln

/**
 * The "brain" of Semmozhi: scores a question against every travel tip.
 *
 * Pure Kotlin (no Android classes) so it can be unit-tested on a normal computer.
 *
 *  - Rare keywords score higher than common ones.
 *  - Stop words are ignored, "weak" words count for much less.
 *  - Multi-word keywords ("lost wallet") and hyphenated ones ("check-in") match.
 *  - Light stemming (travel / travelling, plan / planning).
 *  - Topic words give a small tie-break bonus.
 *  - Distress words / phrases always return the Emergency tip.
 *  - A tip may have several answers; one is picked at random.
 *  - Works with Tamil and Hindi script keywords too.
 */
class TipMatcher(tips: List<TravelTip>, private val answerSeparator: String = "\u001F") {

    private class IndexedTip(
        val tip: TravelTip,
        val answers: List<String>,
        val phrases: List<List<String>>,
        val topicTokens: List<String>
    )

    private val indexed: List<IndexedTip>
    private val weights: Map<List<String>, Double>

    init {
        indexed = tips.map { tip ->
            IndexedTip(
                tip = tip,
                answers = tip.answer.split(answerSeparator).map { it.trim() }.filter { it.isNotEmpty() },
                phrases = tip.keywords.map { tokenize(it) }.filter { it.isNotEmpty() }.distinct(),
                topicTokens = tokenize(tip.topic)
            )
        }

        val docFreq = HashMap<List<String>, Int>()
        for (item in indexed) for (phrase in item.phrases) {
            docFreq[phrase] = (docFreq[phrase] ?: 0) + 1
        }

        val total = indexed.size.coerceAtLeast(1).toDouble()
        weights = docFreq.mapValues { (phrase, df) ->
            var w = ln(total / df) + 1.0
            if (phrase.size > 1) w *= phrase.size
            if (phrase.size == 1 && phrase[0] in WEAK) w *= 0.3
            w
        }
    }

    val size: Int get() = indexed.size

    fun match(question: String): TravelTip? {

        val tokens = tokenize(question)

        if (isDistress(question) || tokens.any { it in URGENT }) {
            emergency()?.let { return it }
        }
        if (tokens.isEmpty()) return null

        var best: IndexedTip? = null
        var bestScore = 0.0

        for (item in indexed) {
            var score = 0.0
            for (phrase in item.phrases) {
                if (containsPhrase(tokens, phrase)) score += weights[phrase] ?: 0.0
            }
            for (topicToken in item.topicTokens) {
                if (topicToken in tokens) score += TOPIC_BONUS
            }
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }

        return if (bestScore >= MIN_SCORE) best?.pick() else null
    }

    private fun emergency(): TravelTip? =
        indexed.firstOrNull { it.tip.topic == "Emergency" }?.pick()

    private fun IndexedTip.pick(): TravelTip =
        tip.copy(answer = answers.randomOrNull() ?: tip.answer)

    private fun containsPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        if (phrase.size == 1) return phrase[0] in tokens
        if (phrase.size > tokens.size) return false
        for (i in 0..tokens.size - phrase.size) {
            if (tokens.subList(i, i + phrase.size) == phrase) return true
        }
        return false
    }

    private fun isDistress(question: String): Boolean {
        val plain = question.lowercase(Locale.ROOT)
        val words = plain.replace(NON_LETTER, " ").split(SPACES).filter { it.isNotEmpty() }
        if ("sos" in words) return true
        if (plain.contains("save me")) return true
        if (CHASED_PHRASES.any { plain.contains(it) }) return true
        return words.size <= 4 && "help" in words
    }

    companion object {

        const val MIN_SCORE = 1.0
        const val TOPIC_BONUS = 1.0

        private val NON_LETTER = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
        private val SPACES = Regex("\\s+")
        private val SUFFIXES = listOf("ing", "ed", "es", "s", "e")

        private val STOP_WORDS = setOf(
            "where", "what", "how", "can", "is", "the", "a", "an", "i", "me",
            "my", "do", "should", "tell", "please", "to", "of", "in", "for",
            "on", "at", "are", "there", "this", "that", "it", "and", "or",
            "with", "be", "need", "want", "about", "near", "any", "some",
            "get", "go", "from", "you", "your", "we", "us", "have", "has",
            "will", "would", "could", "which", "when", "who", "why", "does",
            "did", "am", "was", "were", "been", "so", "if", "not", "no"
        )

        private val WEAK = listOf(
            "help", "find", "place", "safe", "person",
            "public", "plan", "visit", "travel", "trip"
        ).map { stem(it) }.toSet()

        private val CHASED_PHRASES = listOf(
            "following me", "followed me", "being followed", "follows me", "chasing me", "someone is following"
        )

        private val URGENT = listOf(
            "robbed", "robbery", "mugged",
            "attack", "attacked", "assault", "assaulted", "kidnap", "kidnapped",
            "harass", "harassed", "harassment", "threat", "threatened",
            "accident", "injured", "bleeding", "unconscious", "stalked", "stalking",
            "बचाओ", "காப்பாற்றுங்கள்"
        ).map { stem(it) }.toSet()

        fun tokenize(text: String): List<String> =
            text.lowercase(Locale.ROOT)
                .replace(NON_LETTER, " ")
                .split(SPACES)
                .filter { it.isNotEmpty() && it !in STOP_WORDS }
                .map { stem(it) }

        fun stem(word: String): String {
            if (word.length <= 3 || word.all { it.isDigit() }) return word
            for (suffix in SUFFIXES) {
                if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
                    var base = word.dropLast(suffix.length)
                    if (base.length > 3 && base[base.length - 1] == base[base.length - 2]) {
                        base = base.dropLast(1)
                    }
                    return base
                }
            }
            return word
        }
    }
}
