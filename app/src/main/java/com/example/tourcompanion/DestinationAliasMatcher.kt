package com.example.tourcompanion

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Domain model representing a verified alias from the destination_aliases table.
 */
data class DestinationAlias(
    val id: Int = 0,
    val destinationId: Int,
    val canonicalName: String,
    val alias: String,
    val language: String
)

/**
 * Result of matching an input query to a verified destination alias or canonical destination.
 */
data class AliasMatchResult(
    val destinationId: Int,
    val canonicalName: String,
    val matchedAlias: String,
    val language: String,
    val isExactMatch: Boolean
)

data class CompoundRoutingResult(
    val target: AliasMatchResult,
    val origin: AliasMatchResult? = null
)

/**
 * Destination Alias Matcher
 *
 * Implements destination resolution using verified aliases stored in `destination_aliases`.
 * Guarantees:
 * 1. Safe Unicode normalization (NFC)
 * 2. Case-insensitivity for English
 * 3. Preservation of Tamil and Hindi scripts
 * 4. Token/phrase-boundary matching (prevents unsafe substring matching)
 * 5. Specificity ranking: exact alias match > longest alias match
 * 6. Ambiguity protection: ties between different destinations return null (unresolved)
 * 7. Canonical destination name fallback check
 * 8. Never invents or infers unverified destinations
 */
class DestinationAliasMatcher(
    private val aliases: List<DestinationAlias> = emptyList(),
    private val canonicalDestinations: Map<Int, String> = emptyMap()
) {

    /**
     * Resolves a user query to its canonical destination using verified aliases.
     *
     * @param query Raw user input query (e.g. "Mylapore temple", "வள்ளுவர் கோட்டம்")
     * @return [AliasMatchResult] with canonical destination ID and name, or null if unresolved
     */
    fun resolveDestination(query: String?): AliasMatchResult? {
        return resolveCompoundDestinations(query)?.target
    }

    /**
     * Resolves compound origin and target destinations in a single query.
     *
     * In compound queries (e.g. "I'm at Marina Beach. How do I get to Kapaleeshwarar Temple?"),
     * both the target destination and origin destination are extracted.
     *
     * @return [CompoundRoutingResult] containing the target destination and optional origin destination,
     *         or null if unresolved or ambiguous.
     */
    fun resolveCompoundDestinations(query: String?): CompoundRoutingResult? {
        if (query.isNullOrBlank()) return null

        val normQuery = normalizeUnicode(query)
        val lowerQuery = normQuery.lowercase(Locale.ROOT)
        if (lowerQuery.isBlank()) return null

        // 1. Check exact alias match
        val exactMatches = mutableListOf<AliasMatchResult>()
        for (a in aliases) {
            val normAlias = normalizeUnicode(a.alias)
            val lowerAlias = normAlias.lowercase(Locale.ROOT)
            if (lowerQuery == lowerAlias) {
                exactMatches.add(
                    AliasMatchResult(
                        destinationId = a.destinationId,
                        canonicalName = resolveCanonicalName(a.destinationId, a.canonicalName),
                        matchedAlias = a.alias,
                        language = a.language,
                        isExactMatch = true
                    )
                )
            }
        }

        if (exactMatches.isNotEmpty()) {
            val firstDestId = exactMatches[0].destinationId
            val isConsistent = exactMatches.all { it.destinationId == firstDestId }
            if (isConsistent) {
                return CompoundRoutingResult(target = exactMatches[0], origin = null)
            }
            // Ambiguity between exact matches pointing to different destinations -> unresolved
            return null
        }

        // 2. Check phrase / token-boundary match and compound destination resolution
        val allMatches = mutableListOf<LocatedMatch>()

        for (a in aliases) {
            val normAlias = normalizeUnicode(a.alias)
            val lowerAlias = normAlias.lowercase(Locale.ROOT)
            if (lowerAlias.isBlank()) continue

            val canon = resolveCanonicalName(a.destinationId, a.canonicalName)
            allMatches.addAll(findMatches(lowerQuery, lowerAlias, a.alias, a.destinationId, canon, a.language))
        }

        // Also check canonical destination names
        val coveredDestIds = allMatches.map { it.destinationId }.toSet()
        for ((destId, destName) in canonicalDestinations) {
            if (destId in coveredDestIds) continue
            val normName = normalizeUnicode(destName)
            val lowerName = normName.lowercase(Locale.ROOT)
            if (lowerName.isBlank()) continue
            allMatches.addAll(findMatches(lowerQuery, lowerName, destName, destId, destName, "en"))
        }

        if (allMatches.isEmpty()) {
            return null
        }

        val byDest = allMatches.groupBy { it.destinationId }

        // Case A: Exactly one distinct destination mentioned
        if (byDest.size == 1) {
            val matches = byDest.values.first()
            val best = matches.maxByOrNull { it.aliasLength } ?: matches[0]
            return CompoundRoutingResult(
                target = AliasMatchResult(
                    destinationId = best.destinationId,
                    canonicalName = best.canonicalName,
                    matchedAlias = best.matchedAlias,
                    language = best.language,
                    isExactMatch = (lowerQuery == best.matchedAlias.lowercase(Locale.ROOT))
                ),
                origin = null
            )
        }

        // Case B: Compound query with multiple distinct destinations
        data class ScoredCandidate(
            val result: AliasMatchResult,
            val baseScore: Double,
            val totalScore: Double
        )

        val scoredCandidates = mutableListOf<ScoredCandidate>()

        for ((destId, destMatches) in byDest) {
            val bestMatch = destMatches.maxByOrNull { it.aliasLength } ?: destMatches[0]

            // Determine preceding context: exclude earlier destination matches and prior sentences
            val maxPrevDestEnd = allMatches
                .filter { it.destinationId != destId && it.endIndex <= bestMatch.startIndex }
                .maxOfOrNull { it.endIndex } ?: 0

            val fullPreceding = lowerQuery.substring(0, bestMatch.startIndex)
            val lastSentencePunct = maxOf(
                fullPreceding.lastIndexOf('.'),
                fullPreceding.lastIndexOf('?'),
                fullPreceding.lastIndexOf('!'),
                fullPreceding.lastIndexOf(';')
            )
            val contextStart = maxOf(maxPrevDestEnd, if (lastSentencePunct >= 0) lastSentencePunct + 1 else 0)
            val precedingContext = lowerQuery.substring(contextStart, bestMatch.startIndex).trim()

            var baseScore = 0.0

            if (TARGET_INDICATOR_PATTERN.matcher(precedingContext).find()) {
                baseScore += 100.0
            }
            if (ORIGIN_INDICATOR_PATTERN.matcher(precedingContext).find()) {
                baseScore -= 100.0
            }
            if (LOCATION_INDICATOR_PATTERN.matcher(precedingContext).find()) {
                baseScore -= 80.0
            }

            // If immediately followed by "to " (e.g. "[Marina Beach] to [Kapaleeshwarar Temple]")
            val following = lowerQuery.substring(bestMatch.endIndex).trimStart()
            if (following.startsWith("to ") || following.startsWith("to\t")) {
                baseScore -= 60.0
            }

            var totalScore = baseScore
            totalScore += (bestMatch.startIndex / 1000.0)
            totalScore += (bestMatch.aliasLength / 10000.0)

            scoredCandidates.add(
                ScoredCandidate(
                    result = AliasMatchResult(
                        destinationId = destId,
                        canonicalName = bestMatch.canonicalName,
                        matchedAlias = bestMatch.matchedAlias,
                        language = bestMatch.language,
                        isExactMatch = false
                    ),
                    baseScore = baseScore,
                    totalScore = totalScore
                )
            )
        }

        // Verify if directional signal exists to distinguish target from origin
        val hasDirectionalSignal = scoredCandidates.any { kotlin.math.abs(it.baseScore) >= 50.0 }
        if (!hasDirectionalSignal) {
            // Ambiguity protection: multiple destinations listed without directional context
            return null
        }

        scoredCandidates.sortByDescending { it.totalScore }
        val top = scoredCandidates[0]
        val runnerUp = scoredCandidates[1]

        // Ambiguity check: if top candidates have identical base score
        if (top.baseScore == runnerUp.baseScore) {
            return null
        }

        // Origin candidate: distinct destination with negative directional score
        val originCandidate = scoredCandidates
            .filter { it.result.destinationId != top.result.destinationId && it.baseScore < 0.0 }
            .minByOrNull { it.baseScore }

        return CompoundRoutingResult(
            target = top.result,
            origin = originCandidate?.result
        )
    }

    /**
     * Checks if a question mentions a known destination or verified alias.
     */
    fun isDestinationQuery(question: String?): Boolean {
        return resolveDestination(question) != null
    }

    private fun resolveCanonicalName(destinationId: Int, fallbackName: String): String {
        return canonicalDestinations[destinationId]?.takeIf { it.isNotBlank() }
            ?: fallbackName.takeIf { it.isNotBlank() }
            ?: "Destination #$destinationId"
    }

    private data class LocatedMatch(
        val destinationId: Int,
        val canonicalName: String,
        val matchedAlias: String,
        val language: String,
        val aliasLength: Int,
        val startIndex: Int,
        val endIndex: Int
    )

    private fun findMatches(
        text: String,
        searchToken: String,
        rawAlias: String,
        destinationId: Int,
        canonicalName: String,
        language: String
    ): List<LocatedMatch> {
        if (searchToken.isBlank()) return emptyList()
        val escaped = Pattern.quote(searchToken)
        val pattern = Pattern.compile(
            "(?:\\b|[\\s\\p{P}]|^)($escaped)(?:\\b|[\\s\\p{P}]|$)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(text)
        val list = mutableListOf<LocatedMatch>()
        var searchIdx = 0
        while (matcher.find(searchIdx)) {
            val start = matcher.start(1)
            val end = matcher.end(1)
            list.add(
                LocatedMatch(
                    destinationId = destinationId,
                    canonicalName = canonicalName,
                    matchedAlias = rawAlias,
                    language = language,
                    aliasLength = rawAlias.length,
                    startIndex = start,
                    endIndex = end
                )
            )
            searchIdx = maxOf(start + 1, matcher.end())
            if (searchIdx >= text.length) break
        }
        return list
    }

    private fun matchesTokenBoundary(text: String, token: String): Boolean {
        val escaped = Pattern.quote(token)
        // Match token bounded by whitespace, punctuation, Unicode word boundaries, or string boundaries
        val pattern = Pattern.compile(
            "(?:\\b|[\\s\\p{P}]|^)$escaped(?:\\b|[\\s\\p{P}]|$)",
            Pattern.CASE_INSENSITIVE
        )
        return pattern.matcher(text).find()
    }

    private fun normalizeUnicode(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC).trim()
    }

    companion object {
        private val TARGET_INDICATOR_PATTERN = Pattern.compile(
            "(?:\\b|^)(?:to|get\\s+to|go\\s+to|going\\s+to|reach|reaching|visit|visiting|towards|heading\\s+to|how\\s+far\\s+is|how\\s+far\\s+to|distance\\s+to|directions?\\s+to|way\\s+to|route\\s+to|destination|செல்ல|போக|அடைய|நோக்கி|செல்லும்\\s+வழி|எப்படி\\s+போவது|எவ்வளவு\\s+தூரம்|जाने\\s+के\\s+लिए|पहुंचने|तक|की\\s+ओर|रास्ता|कैसे\\s+जाएं|कितनी\\s+दूर)(?:\\b|$)",
            Pattern.CASE_INSENSITIVE
        )

        private val ORIGIN_INDICATOR_PATTERN = Pattern.compile(
            "(?:\\b|^)(?:from|starting\\s+from|departing\\s+from|leaving|origin|இருந்து|கிளம்பி|தொடங்கி|से|निकलकर|शुरू)(?:\\b|$)",
            Pattern.CASE_INSENSITIVE
        )

        private val LOCATION_INDICATOR_PATTERN = Pattern.compile(
            "(?:\\b|^)(?:i'm\\s+at|im\\s+at|i\\s+am\\s+at|at|i'm\\s+near|im\\s+near|i\\s+am\\s+near|near|nearby|i'm\\s+in|im\\s+in|i\\s+am\\s+in|in|currently\\s+at|staying\\s+at|இருக்கிறேன்|அருகில்|அருகே|பக்கத்தில்|உள்ளேன்|पर\\s+हूँ|पर\\s+हूं|के\\s+पास|में\\s+हूँ|में\\s+हूं|मौजूद)(?:\\b|$)",
            Pattern.CASE_INSENSITIVE
        )

        /**
         * Creates a [DestinationAliasMatcher] from aliases retrieved via [DestinationApiClient].
         */
        fun fromAliases(
            aliases: List<DestinationAlias>,
            canonicalMap: Map<Int, String> = emptyMap()
        ): DestinationAliasMatcher {
            val fullCanonicalMap = canonicalMap.toMutableMap()
            for (a in aliases) {
                if (a.canonicalName.isNotBlank() && !fullCanonicalMap.containsKey(a.destinationId)) {
                    fullCanonicalMap[a.destinationId] = a.canonicalName
                }
            }
            return DestinationAliasMatcher(aliases, fullCanonicalMap)
        }
    }
}
