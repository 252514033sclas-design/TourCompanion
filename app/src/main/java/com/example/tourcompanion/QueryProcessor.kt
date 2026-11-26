package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================
// Travel Intent Classification
// ============================================================

enum class TravelIntent {
    EMERGENCY,
    DESTINATION_DISCOVERY,
    DESTINATION_DETAILS,
    DESTINATION_TIMING,
    ACTIVITY_PLANNING,
    NEARBY_ATTRACTIONS,
    TRANSPORT_NEARBY,
    TRANSPORT_ROUTE_SEARCH,
    TRANSPORT_ROUTE_TIMING,
    DISTANCE_DIRECTIONS,
    LOST_DISORIENTED,
    SAFETY,
    TRAVEL_TIP,
    BUDGET_TRAVEL,
    TIME_CONSTRAINED_PLANNING,
    SMALL_TALK,
    UNKNOWN
}

data class TravelIntentResult(
    val intent: TravelIntent,
    val requiresLocation: Boolean = false,
    val requiresDestinationData: Boolean = false,
    val requiresTransportData: Boolean = false,
    val requiresGemma: Boolean = false,
    val transportIntent: TransportIntent? = null
)

enum class TransportSubIntent {
    NONE,
    TRANSPORT_NEARBY,
    TRANSPORT_ROUTE_SEARCH,
    TRANSPORT_STOP_ROUTE_LOOKUP,
    TRANSPORT_ROUTE_TIMING
}

data class TransportIntent(
    val subIntent: TransportSubIntent,
    val target: String? = null,
    val mode: String? = null,
    val origin: String? = null,
    val destination: String? = null
)

/**
 * Loads the travel tips from the SQLite database and answers questions with TipMatcher.
 * Also provides deterministic transport intent classification for Chennai public transit.
 *
 * Call warmUp() from a background thread (the splash screen does) so the first
 * question never does database work on the main thread.
 */
class QueryProcessor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var matcher: TipMatcher? = null

    private fun getMatcher(): TipMatcher {
        return matcher ?: synchronized(this) {
            matcher ?: TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP).also { matcher = it }
        }
    }

    fun warmUp(client: DestinationApiClient = DestinationApiClient()) {
        check(getMatcher().size > 0) { "travel_tips table is empty" }
        initAliasesAsync(client)
    }

    suspend fun syncAliases(client: DestinationApiClient = DestinationApiClient()): Boolean =
        Companion.syncAliases(client)

    fun initAliasesAsync(client: DestinationApiClient = DestinationApiClient()) =
        Companion.initAliasesAsync(client)

    fun reload() {
        synchronized(this) {
            matcher = TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP)
        }
    }

    fun processQuery(question: String): TravelTip? = getMatcher().match(question)

    fun isDestinationQuery(question: String): Boolean = Companion.isDestinationQuery(question)

    fun resolveDestination(question: String): AliasMatchResult? = Companion.resolveDestination(question)

    fun resolveAnaphora(question: String): String = Companion.resolveAnaphora(question)

    fun hasAnaphoraReference(question: String): Boolean = Companion.hasAnaphoraReference(question)

    fun clearSessionDestination() = Companion.clearSessionDestination()

    fun resolveCompoundDestinations(question: String): CompoundRoutingResult? =
        Companion.resolveCompoundDestinations(question)

    fun detectTransportIntent(question: String): TransportIntent = Companion.detectTransportIntent(question)

    fun classifyTravelIntent(question: String): TravelIntentResult = Companion.classifyTravelIntent(question)

    suspend fun processTransportQuery(
        intent: TransportIntent,
        repository: TransportRepository,
        userLat: Double? = null,
        userLon: Double? = null,
        mtcProvider: TransportTimingProvider = AppServices.mtcTransportProvider()
    ): String = Companion.processTransportQuery(intent, repository, userLat, userLon, mtcProvider)

    private fun loadTips(): List<TravelTip> {
        val helper = TravelDatabaseHelper(appContext)
        val result = ArrayList<TravelTip>()

        try {
            helper.readableDatabase
                .rawQuery("SELECT topic, keywords, answer FROM travel_tips ORDER BY id", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        result += TravelTip(
                            topic = cursor.getString(0),
                            keywords = cursor.getString(1)
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            answer = cursor.getString(2)
                        )
                    }
                }
        } finally {
            helper.close()
        }

        return result
    }

    companion object {
        private val STOP_ROUTE_LOOKUP_REGEX = Regex(
            "(?:what|which)?\\s*(?:buses|routes|trains|bus|route)\\s*(?:stop|stops|stopping|call|calls)\\s+(?:at|in)\\s+([^?]+)",
            RegexOption.IGNORE_CASE
        )

        private val ROUTES_SERVE_REGEX = Regex(
            "(?:what|which)?\\s*(?:routes?|buses?)\\s*(?:serve|serves|serving)\\s+([^?]+)",
            RegexOption.IGNORE_CASE
        )

        private val ROUTE_CODE_REGEX = Regex(
            "^(?:AC[ -]?)?[0-9]{1,8}[A-Za-z]{0,3}$|^[A-Za-z]{1,3}[ -]?[0-9]{1,8}[A-Za-z]{0,3}$",
            RegexOption.IGNORE_CASE
        )

        private val NON_ROUTE_WORDS = setOf(
            "1st", "2nd", "3rd", "am", "pm", "km", "m", "hour", "hours", "min", "mins"
        )

        private val EXPLICIT_ROUTE_PREFIX_REGEX = Regex(
            "\\b(?:route|bus|bus\\s+no\\.?|bus\\s+number|route\\s+no\\.?|route\\s+number|bus\\s+is|route\\s+is)\\s+([0-9a-zA-Z\\-]+)\\b",
            RegexOption.IGNORE_CASE
        )

        private val STANDALONE_ROUTE_CODE_QUERY_REGEX = Regex(
            "^(?:what is|tell me about|info on|details for|about)?\\s*(?:the\\s+)?([0-9]{1,8}[A-Za-z]{0,3}|[A-Za-z]{1,3}[0-9]{1,8}[A-Za-z]{0,3})\\??$",
            RegexOption.IGNORE_CASE
        )

        private val METRO_LINE_REGEX = Regex(
            "\\b(blue|green)\\s+line\\b",
            RegexOption.IGNORE_CASE
        )

        private val NEARBY_PHRASES = listOf(
            "near me", "nearest", "nearby", "around here", "close to me", "closest",
            "how do i find a bus", "how to find a bus",
            "where can i catch a bus", "where to catch a bus",
            "where can i find a bus", "where to find a bus",
            "show buses near me", "show transport near me"
        )

        private val TRANSPORT_MODES = listOf("bus", "buses", "metro", "subway", "train", "transport", "transit")

        private val DESTINATION_PATTERNS = listOf(
            listOf("marina beach", "marina", "மெரினா", "மரினா", "मरीना बीच", "मरीना"),
            listOf("kapaleeshwarar", "kapaleeswarar", "kapaleeswar", "mylapore temple", "arulmigu kapaleeshwarar", "கபாலீஸ்வரர்", "कपालेश्वर", "कपालीश्वर"),
            listOf("fort st george", "fort st. george", "st mary's church", "st marys church", "st mary", "புனித ஜார்ஜ்", "फोर्ट सेंट जॉर्ज"),
            listOf("government museum", "egmore museum", "national art gallery", "madras museum", "அருங்காட்சியகம்", "எழும்பூர் அருங்காட்சியகம்", "एग्मोर संग्रहालय"),
            listOf("besant nagar beach", "besant nagar", "elliot's beach", "elliots beach", "elliot beach", "bessy beach", "பெசன்ட் நகர்", "इलियट्स बीच", "इलियट्स"),
            listOf("valluvar kottam", "valluvar", "வள்ளுவர் கோட்டம்", "வள்ளுவர்", "वल्लुवर कोट्टम", "वल्लुवर")
        )

        private const val TAG = "QueryProcessor"

        @Volatile
        var aliasMatcher: DestinationAliasMatcher? = null

        private val isSyncingAliases = AtomicBoolean(false)

        /**
         * Fetches verified destination aliases from the backend API via [DestinationApiClient]
         * and initializes [aliasMatcher] using [DestinationAliasMatcher.fromAliases].
         *
         * Returns true if aliases were successfully fetched and matcher updated, false otherwise.
         * Thread-safe and non-blocking in coroutine context.
         */
        suspend fun syncAliases(client: DestinationApiClient = DestinationApiClient()): Boolean = withContext(Dispatchers.IO) {
            if (!isSyncingAliases.compareAndSet(false, true)) {
                return@withContext false
            }
            try {
                val response = client.getDestinationAliases()
                if (response is DestinationApiClient.Response.Success) {
                    val aliases = response.data
                    if (aliases.isNotEmpty()) {
                        val matcher = DestinationAliasMatcher.fromAliases(aliases)
                        aliasMatcher = matcher
                        logD(TAG, "Initialized aliasMatcher with ${aliases.size} verified destination aliases.")
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                logW(TAG, "Unable to fetch destination aliases from backend: ${e.message}")
                false
            } finally {
                isSyncingAliases.set(false)
            }
        }

        /**
         * Asynchronously initializes [aliasMatcher] on a background daemon thread
         * without blocking the Android main/UI thread.
         */
        fun initAliasesAsync(client: DestinationApiClient = DestinationApiClient()) {
            if (aliasMatcher != null) return
            Thread {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        syncAliases(client)
                    }
                }
            }.start()
        }

        private fun logD(tag: String, msg: String) {
            try {
                Log.d(tag, msg)
            } catch (_: Throwable) {
                println("[$tag] $msg")
            }
        }

        private fun logW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: Throwable) {
                System.err.println("[$tag] $msg")
            }
        }

        @Volatile
        var lastResolvedDestination: AliasMatchResult? = null

        fun clearSessionDestination() {
            lastResolvedDestination = null
        }

        private val ANAPHORA_PHRASES = listOf(
            "that place", "this place", "that destination", "this destination"
        )

        private val IMPERSONAL_IT_REGEX = Regex(
            "\\b(?:is|was|will\\s+it\\s+be)\\s+it\\s+(?:safe|okay|ok|good|better|easy|possible|necessary|advisable|recommended)\\s+to\\b",
            RegexOption.IGNORE_CASE
        )

        fun hasAnaphoraReference(question: String): Boolean {
            val lower = question.lowercase(Locale.ROOT).trim()
            if (lower.isEmpty()) return false

            if (ANAPHORA_PHRASES.any { lower.contains(it) }) return true
            if (Regex("\\bthere\\b").containsMatchIn(lower) &&
                !Regex("\\b(?:is|are|was|were)\\s+there\\b").containsMatchIn(lower)
            ) {
                return true
            }
            if (Regex("\\bit\\b").containsMatchIn(lower) && !IMPERSONAL_IT_REGEX.containsMatchIn(lower)) {
                return true
            }
            return false
        }

        fun hasExplicitDestination(question: String): Boolean {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return false
            if (aliasMatcher?.resolveDestination(trimmed) != null) return true
            val lower = trimmed.lowercase(Locale.ROOT)
            return DESTINATION_PATTERNS.any { aliases ->
                aliases.any { alias -> lower.contains(alias) }
            }
        }

        fun resolveExplicitDestination(question: String): AliasMatchResult? {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return null
            aliasMatcher?.resolveDestination(trimmed)?.let { return it }

            // Fallback to built-in verified destination patterns
            val lower = trimmed.lowercase(Locale.ROOT)
            val canonNames = listOf(
                Pair(1, "Marina Beach"),
                Pair(2, "Arulmigu Kapaleeshwarar Temple"),
                Pair(3, "Fort St. George"),
                Pair(4, "Government Museum"),
                Pair(5, "Besant Nagar Beach"),
                Pair(6, "Valluvar Kottam")
            )
            for ((idx, patternList) in DESTINATION_PATTERNS.withIndex()) {
                val matched = patternList.firstOrNull { lower.contains(it) }
                if (matched != null) {
                    val (destId, canon) = canonNames[idx]
                    return AliasMatchResult(
                        destinationId = destId,
                        canonicalName = canon,
                        matchedAlias = matched,
                        language = "en",
                        isExactMatch = (lower == matched)
                    )
                }
            }
            return null
        }

        fun resolveAnaphora(question: String): String {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return trimmed

            // Explicit destination always takes priority
            if (hasExplicitDestination(trimmed)) {
                return trimmed
            }

            val current = lastResolvedDestination ?: return trimmed
            val destName = current.canonicalName
            val lower = trimmed.lowercase(Locale.ROOT)
            var result = trimmed

            // 1. Multi-word phrases
            if (Regex("\\bthat\\s+place\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                result = result.replace(Regex("\\bthat\\s+place\\b", RegexOption.IGNORE_CASE), destName)
            }
            if (Regex("\\bthis\\s+place\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                result = result.replace(Regex("\\bthis\\s+place\\b", RegexOption.IGNORE_CASE), destName)
            }
            if (Regex("\\bthat\\s+destination\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                result = result.replace(Regex("\\bthat\\s+destination\\b", RegexOption.IGNORE_CASE), destName)
            }
            if (Regex("\\bthis\\s+destination\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                result = result.replace(Regex("\\bthis\\s+destination\\b", RegexOption.IGNORE_CASE), destName)
            }

            // 2. Pronoun "it" (standalone word, when not impersonal "is it <adj> to <verb>")
            if (Regex("\\bit\\b", RegexOption.IGNORE_CASE).containsMatchIn(result) &&
                !IMPERSONAL_IT_REGEX.containsMatchIn(lower)
            ) {
                result = result.replace(Regex("\\bit\\b", RegexOption.IGNORE_CASE), destName)
            }

            // 3. Adverb "there" (when not existential "is there / are there")
            if (Regex("\\bthere\\b", RegexOption.IGNORE_CASE).containsMatchIn(result) &&
                !Regex("\\b(?:is|are|was|were)\\s+there\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            ) {
                if (Regex("\\b(?:go|get|travel|head)\\s+there\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                    result = result.replace(Regex("\\b(go|get|travel|head)\\s+there\\b", RegexOption.IGNORE_CASE), "$1 to $destName")
                } else if (Regex("\\breach\\s+there\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                    result = result.replace(Regex("\\breach\\s+there\\b", RegexOption.IGNORE_CASE), "reach $destName")
                } else if (Regex("\\b(?:do|see|visit|eat|stay|enjoy)\\s+there\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                    result = result.replace(Regex("\\b(do|see|visit|eat|stay|enjoy)\\s+there\\b", RegexOption.IGNORE_CASE), "$1 at $destName")
                } else if (Regex("\\b(?:at|in)\\s+there\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
                    result = result.replace(Regex("\\b(?:at|in)\\s+there\\b", RegexOption.IGNORE_CASE), "at $destName")
                } else {
                    result = result.replace(Regex("\\bthere\\b", RegexOption.IGNORE_CASE), "at $destName")
                }
            }

            return result
        }

        fun resolveDestination(question: String): AliasMatchResult? {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return null

            // 1. Explicit destination takes priority
            val explicitMatch = resolveExplicitDestination(trimmed)
            if (explicitMatch != null) {
                lastResolvedDestination = explicitMatch
                return explicitMatch
            }

            // 2. Contextual anaphora resolution
            if (hasAnaphoraReference(trimmed)) {
                return lastResolvedDestination
            }

            return null
        }

        fun resolveCompoundDestinations(question: String): CompoundRoutingResult? {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return null
            return aliasMatcher?.resolveCompoundDestinations(trimmed)
        }

        fun isDestinationQuery(question: String): Boolean {
            val lower = question.lowercase(Locale.ROOT).trim()
            if (lower.isEmpty()) return false

            // Priority 1: Check verified destination aliases
            aliasMatcher?.let { matcher ->
                if (matcher.isDestinationQuery(question)) {
                    return true
                }
            }

            // Priority 2: Fallback to existing destination patterns
            if (DESTINATION_PATTERNS.any { aliases -> aliases.any { alias -> lower.contains(alias) } }) {
                return true
            }

            // Priority 3: Contextual destination memory
            if (hasAnaphoraReference(question) && lastResolvedDestination != null) {
                return true
            }

            return false
        }

        fun isValidRouteCode(candidate: String, hasTransitContext: Boolean = false): Boolean {
            val clean = candidate.trim().trimEnd('?', '.', '!', ',', ';', ':')
            if (clean.isEmpty()) return false
            if (!clean.any { it.isDigit() }) return false
            if (clean.lowercase(Locale.ROOT) in NON_ROUTE_WORDS) return false
            if (!clean.matches(ROUTE_CODE_REGEX)) return false
            // Pure numbers (e.g. 500, 100) require transit context ("bus" or "route") to avoid
            // false-matching currency amounts, hours, emergency numbers, etc.
            if (clean.all { it.isDigit() } && !hasTransitContext) {
                return false
            }
            return true
        }

        fun findRouteCode(text: String): String? {
            val lower = text.lowercase(Locale.ROOT)
            val hasTransitContext = lower.contains("bus") || lower.contains("route") || lower.contains("transit")

            val prefixMatch = EXPLICIT_ROUTE_PREFIX_REGEX.find(text)
            if (prefixMatch != null) {
                val cand = prefixMatch.groupValues[1].trim()
                if (isValidRouteCode(cand, hasTransitContext = true)) {
                    return cand
                }
            }

            val standaloneMatch = STANDALONE_ROUTE_CODE_QUERY_REGEX.find(text)
            if (standaloneMatch != null) {
                val cand = standaloneMatch.groupValues[1].trim()
                if (isValidRouteCode(cand, hasTransitContext = hasTransitContext)) {
                    return cand
                }
            }

            val tokens = text.split(Regex("[^0-9a-zA-Z\\-]+"))
            for (token in tokens) {
                if (isValidRouteCode(token, hasTransitContext = hasTransitContext)) {
                    return token
                }
            }

            return null
        }

        fun cleanPlaceName(raw: String): String? {
            val cleaned = raw.trim()
                .replace(Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(?:get|go|reach|travel)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(?:by\\s+bus|by\\s+metro|by\\s+train|by\\s+transit|by\\s+subway)\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:suggest\\s+(?:the\\s+|a\\s+)?route|suggest\\s+route|from\\s+me|from\\s+here|please)\\b.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(?:bus(?!\\s+(?:stand|terminus|stop|depot|station))|route)\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+ku$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim('?', '.', '!', ',', ' ')
            if (cleaned.isBlank() ||
                cleaned.equals("there", ignoreCase = true) ||
                cleaned.equals("here", ignoreCase = true) ||
                cleaned.equals("get there", ignoreCase = true) ||
                cleaned.equals("go there", ignoreCase = true)
            ) {
                return null
            }
            return cleaned
        }

        fun normalizeTransitPlace(name: String?): String? {
            if (name.isNullOrBlank()) return name
            val trimmed = name.trim()
            val lower = trimmed.lowercase(Locale.ROOT)
            return when {
                lower == "chennai rail museum" || lower == "rail museum" -> "Rail Museum"
                lower == "government museum, egmore" || lower == "government museum" || lower == "egmore museum" -> "Egmore Musem"
                else -> trimmed
            }
        }

        fun extractCandidateTarget(question: String): String? {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return null

            // 1. Compound routing: "at X and want to go to Y" or "from X to Y"
            val compoundMatch = Regex(
                "\\b(?:from|at)\\s+([^?]+?)\\s+(?:and\\s+)?(?:i\\s+)?(?:want\\s+to\\s+)?(?:get|go|reach|travel)?\\s*to\\s+([^?.,!]+)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (compoundMatch != null) {
                cleanPlaceName(compoundMatch.groupValues[2])?.let { return it }
            }

            // 2. "where is <target>"
            val whereMatch = Regex("\\bwhere\\s+is\\s+([^?.,!]+)", RegexOption.IGNORE_CASE).find(trimmed)
            if (whereMatch != null) {
                cleanPlaceName(whereMatch.groupValues[1])?.let { return it }
            }

            // 3. "how far is <target> from me / away"
            val howFarMatch = Regex(
                "\\bhow\\s+far\\s+(?:is\\s+)?([^?.,!]+?)(?:\\s+from\\s+me|\\s+away|\\?|$)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (howFarMatch != null) {
                cleanPlaceName(howFarMatch.groupValues[1])?.let { return it }
            }

            // 4. "how to get/go/reach to <target>" or "want to go/reach/get to <target>"
            val toMatch = Regex(
                "\\b(?:how\\s+(?:do\\s+i|can\\s+i|to)\\s+)?(?:want\\s+to\\s+)?(?:go|reach|get|travel)\\s+to\\s+([^?.,!]+)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (toMatch != null) {
                cleanPlaceName(toMatch.groupValues[1])?.let { return it }
            }

            // 5. "directions/route/distance/way to <target>"
            val dirMatch = Regex(
                "\\b(?:directions|route|distance|way|navigate)\\s+to\\s+([^?.,!]+)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (dirMatch != null) {
                cleanPlaceName(dirMatch.groupValues[1])?.let { return it }
            }

            // 6. "to go to <target>"
            val toGoMatch = Regex(
                "\\bto\\s+(?:go|reach|get|travel)\\s+to\\s+([^?.,!]+)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (toGoMatch != null) {
                cleanPlaceName(toGoMatch.groupValues[1])?.let { return it }
            }

            // 7. Tanglish routing: "<target> ku epdi/eppadi poganum/poga/..."
            val tanglishDirMatch = Regex(
                "([^?.,!]+?)\\s+ku\\s+(?:e?pdi|eppadi)\\s+(?:poganum|poga|pogalam|porathu|reach\\s+pan+rathu|reach\\s+pan+drathu)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (tanglishDirMatch != null) {
                cleanPlaceName(tanglishDirMatch.groupValues[1])?.let { return it }
            }

            return extractOriginDestination(trimmed).second
        }

        fun extractOriginDestination(text: String): Pair<String?, String?> {
            var extractedOrigin: String? = null
            var extractedDest: String? = null

            val fromToMatch = Regex("\\bfrom\\s+([^?]+?)\\s+to\\s+(?:(?:get|go|reach|travel)\\s+to\\s+)?([^?]+)", RegexOption.IGNORE_CASE).find(text)
            if (fromToMatch != null) {
                extractedOrigin = cleanPlaceName(fromToMatch.groupValues[1])
                extractedDest = cleanPlaceName(fromToMatch.groupValues[2])
            } else {
                val fromMatch = Regex("\\bfrom\\s+([^?]+)", RegexOption.IGNORE_CASE).find(text)
                if (fromMatch != null) {
                    extractedOrigin = cleanPlaceName(fromMatch.groupValues[1])
                }
                val toMatch = Regex("\\b(?:to\\s+(?:get|go|reach|travel)\\s+to|to)\\s+([^?]+)", RegexOption.IGNORE_CASE).find(text)
                if (toMatch != null) {
                    extractedDest = cleanPlaceName(toMatch.groupValues[1])
                }
            }

            return Pair(extractedOrigin, extractedDest)
        }

        fun detectTransportIntent(question: String): TransportIntent {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return TransportIntent(TransportSubIntent.NONE)

            val lower = trimmed.lowercase(Locale.ROOT)

            // 1. Distress / Emergency check - NEVER classify emergency as transport
            if (lower.contains("sos") || lower.contains("112") || lower.contains("save me") ||
                lower.contains("following me") || lower.contains("robbed") || lower.contains("attack") ||
                lower.contains("accident") || lower.contains("injured") || lower.contains("bleeding") ||
                lower.contains("assault") || lower.contains("kidnap") || lower.contains("stalk") ||
                lower.contains("காப்பாற்றுங்கள்") || lower.contains("बचाओ")
            ) {
                return TransportIntent(TransportSubIntent.NONE)
            }

            // 2. NEARBY TRANSPORT (check first so "nearest bus stop" or "bus stop near me" are never confused with stop lookup)
            val hasTransportNoun = TRANSPORT_MODES.any { lower.contains(it) }
            if (hasTransportNoun) {
                val hasNearbyKeyword = NEARBY_PHRASES.any { lower.contains(it) } ||
                    (lower.contains("near") && !lower.contains("marina beach") && !lower.contains("temple")) ||
                    lower.startsWith("where is the nearest") ||
                    lower.startsWith("where are the nearest") ||
                    lower.startsWith("is there a metro") ||
                    lower.startsWith("is there a bus")

                if (hasNearbyKeyword) {
                    val mode = when {
                        lower.contains("metro") || lower.contains("subway") -> "subway"
                        lower.contains("bus") -> "bus"
                        else -> null
                    }
                    return TransportIntent(TransportSubIntent.TRANSPORT_NEARBY, mode = mode)
                }
            }

            // 3. STOP-ROUTE LOOKUP ("What buses stop at Broadway?", "Which routes serve MTC-840?")
            val stopMatch = STOP_ROUTE_LOOKUP_REGEX.find(trimmed)
            if (stopMatch != null) {
                val target = stopMatch.groupValues[1].trim().trimEnd('?', '.', '!', ' ')
                if (target.isNotEmpty()) {
                    return TransportIntent(TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP, target = target)
                }
            }
            val routesServeMatch = ROUTES_SERVE_REGEX.find(trimmed)
            if (routesServeMatch != null) {
                val target = routesServeMatch.groupValues[1].trim().trimEnd('?', '.', '!', ' ')
                if (target.isNotEmpty()) {
                    return TransportIntent(TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP, target = target)
                }
            }
            if (lower.contains("routes serve") || lower.contains("buses serve") || lower.contains("routes serving")) {
                val words = trimmed.split(Regex("\\s+"))
                val target = words.lastOrNull()?.trimEnd('?', '.', '!', ' ')
                if (!target.isNullOrEmpty()) {
                    return TransportIntent(TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP, target = target)
                }
            }

            // 4. TIMING SEARCH ("What time is the next 5C bus from Adyar to Royapuram?", "When is the next 66 bus?", "timing for 21G")
            val isTiming = lower.contains("time") || lower.contains("timing") ||
                lower.contains("schedule") || lower.contains("when is") ||
                lower.contains("when does") || lower.contains("departure") ||
                lower.contains("what time")

            val (extractedOrigin, extractedDest) = extractOriginDestination(trimmed)

            val tanglishModeRegex = Regex("\\b(?:bus|metro|train|subway)\\s+la\\b", RegexOption.IGNORE_CASE)
            val hasTanglishMode = tanglishModeRegex.containsMatchIn(lower)

            var origin = extractedOrigin
            var dest = extractedDest

            if (hasTanglishMode && dest == null) {
                val tanglishFromTo = Regex("([^?.,!]+?)\\s+(?:la\\s+irun[dt]hu|lerundhu|lendhu)\\s+([^?.,!]+?)\\s+ku\\b", RegexOption.IGNORE_CASE).find(trimmed)
                if (tanglishFromTo != null) {
                    if (origin == null) {
                        origin = cleanPlaceName(tanglishFromTo.groupValues[1])
                    }
                    dest = cleanPlaceName(tanglishFromTo.groupValues[2])
                } else {
                    val tanglishDestMatch = Regex("([^?.,!]+?)\\s+ku\\b", RegexOption.IGNORE_CASE).find(trimmed)
                    if (tanglishDestMatch != null) {
                        val candidate = tanglishDestMatch.groupValues[1]
                            .replace(tanglishModeRegex, "")
                            .replace(Regex("^(?:e?pdi|eppadi)\\s+", RegexOption.IGNORE_CASE), "")
                            .trim()
                        dest = cleanPlaceName(candidate)
                    }
                }
            }

            if (isTiming) {
                // If asking about metro timing
                val metroTimingMatch = METRO_LINE_REGEX.find(trimmed)
                if (metroTimingMatch != null) {
                    val line = metroTimingMatch.groupValues[1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                    return TransportIntent(
                        subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
                        target = line,
                        mode = "subway"
                    )
                }

                val routeCandidate = findRouteCode(trimmed)
                if (routeCandidate != null) {
                    return TransportIntent(
                        subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
                        target = routeCandidate,
                        mode = "bus",
                        origin = origin,
                        destination = dest
                    )
                }
            }

            // 5. ROUTE SEARCH
            // Metro line check (e.g. "Blue Line metro", "Tell me about the Blue Line", "Green Line")
            val metroMatch = METRO_LINE_REGEX.find(trimmed)
            if (metroMatch != null) {
                val line = metroMatch.groupValues[1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                return TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = line, mode = "subway")
            }

            // Explicit or standalone route code (e.g. "21G", "bus 102", "route AC-21G", "What is 5C?")
            val explicitRoute = findRouteCode(trimmed)

            val hasTanglishCue = hasTanglishMode && (
                dest != null || origin != null ||
                lower.contains("take") || lower.contains("use") || lower.contains("catch") ||
                lower.contains("get to") || lower.contains("go to") || lower.contains("travel to") ||
                lower.contains("epdi") || lower.contains("eppadi") ||
                lower.contains("poganum") || lower.contains("poga") ||
                lower.contains("porathu") || lower.contains("reach") ||
                DIRECTIONS_TANGLISH.any { lower.contains(it) } ||
                DIRECTIONS_TANGLISH_REGEX.containsMatchIn(lower)
            )

            // Natural transit route query patterns
            val isTransitRouteQuery =
                explicitRoute != null ||
                hasTanglishCue ||
                lower.contains("which bus") || lower.contains("what bus") ||
                lower.contains("which metro") || lower.contains("what metro") ||
                lower.contains("which route") || lower.contains("what route") ||
                lower.contains("by bus") || lower.contains("by metro") ||
                lower.contains("by subway") || lower.contains("by train") ||
                ((lower.contains("bus") || lower.contains("metro") || lower.contains("subway") || lower.contains("train")) &&
                 (origin != null || dest != null ||
                  lower.contains("take") || lower.contains("use") || lower.contains("catch") || lower.contains("get to") || lower.contains("go to") || lower.contains("travel to")))

            if (isTransitRouteQuery) {
                val mode = when {
                    lower.contains("metro") || lower.contains("subway") -> "subway"
                    lower.contains("train") || lower.contains("rail") -> "rail"
                    lower.contains("bus") -> "bus"
                    else -> null
                }
                return TransportIntent(
                    subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
                    target = explicitRoute,
                    mode = mode,
                    origin = origin,
                    destination = dest
                )
            }

            return TransportIntent(TransportSubIntent.NONE)
        }

        // ============================================================
        // TravelIntent Classification
        // ============================================================

        // ---- Emergency phrase lists (multilingual) ------------------
        private val EMERGENCY_EN = listOf(
            "sos", "112", "999", "emergency", "help me", "i need help",
            "save me", "in danger", "being attacked", "being robbed",
            "someone is following", "following me", "someone following",
            "i am being stalked", "being stalked", "stalked",
            "robbed", "attack", "attacked", "accident", "injured",
            "bleeding", "assault", "assaulted", "kidnap", "kidnapped",
            "ambulance", "police", "distress",
            "fire!", "fire help", "i'm lost and scared", "lost and scared",
            "i need emergency"
        )
        private val EMERGENCY_HI = listOf(
            "बचाओ", "मदद", "आपातकाल", "आपातकालीन", "खतरा", "बचाइये",
            "मुझे मदद", "हमला", "चोरी"
        )
        private val EMERGENCY_TA = listOf(
            "காப்பாற்றுங்கள்", "உதவி", "ஆபத்து", "தீ", "அவசரநிலை"
        )

        // ---- Destination timing signals ----------------------------
        private val TIMING_EN = listOf(
            "open", "opening", "opens", "close", "closing", "closes",
            "timing", "timings", "hours", "visiting hours", "visiting time",
            "what time", "when is", "when does", "when do", "when are",
            "what are the hours", "open time", "open hours"
        )
        private val TIMING_HI = listOf("समय", "कब", "कितने बजे", "खुलता", "बंद होता")
        private val TIMING_TA = listOf("நேரம்", "திறக்கும்", "மூடும்", "நேரங்கள்", "திறக்கிறது")

        // ---- Destination discovery signals -------------------------
        private val DISCOVERY_EN = listOf(
            "places to visit", "places to see", "tourist attractions", "places in chennai",
            "what to see", "suggest places", "where should i go", "where can i go",
            "top places", "best places", "tourist spots", "must visit",
            "sightseeing", "what places", "which places", "good places",
            "famous places", "popular places", "where can i visit",
            "places to explore", "interesting places",
            "visit a", "explore a"
        )

        // ---- Activity planning -------------------------------------
        private val ACTIVITY_EN = listOf(
            "what can i do", "things to do", "activities", "activity",
            "what to do", "what should i do at", "how to enjoy",
            "fun things", "how can i spend"
        )

        // ---- Nearby attractions (non-transport) --------------------
        private val NEARBY_PROXIMITY_REGEX = Regex(
            "\\b(?:near\\s+me|near\\s+my\\s+location|near\\s+here|nearby|near\\s+by|around\\s+me|around\\s+here|around\\s+us|close\\s+to\\s+me|close\\s+by|close\\s+to\\s+here|in\\s+this\\s+area|in\\s+my\\s+area|in\\s+the\\s+area|what(?:'s|\\s+is|s)?\\s+near(?:by)?)\\b",
            RegexOption.IGNORE_CASE
        )
        private val NEARBY_HI = listOf("पास में", "नज़दीक", "नजदीक", "आसपास", "मेरे पास", "यहाँ पास में", "इधर पास में")
        private val NEARBY_TA = listOf(
            "அருகில்", "அருகே", "பக்கத்தில்", "சுற்றிலும்",
            "என்னைச் சுற்றி", "என்னை சுற்றி", "என்னைச் சுற்றிலும்", "என்னை சுற்றிலும்"
        )
        private val NEARBY_TA_REGEX = Regex(
            "(?:என்னை[\\s\\u200B-\\u200D]*(?:ச்)?[\\s\\u200B-\\u200D]*சுற்றி(?:லும்)?|அருகில்|அருகே|பக்கத்தில்|சுற்றிலும்)"
        )
        private val NEARBY_TANGLISH = listOf(
            "pakkathula", "pakathula", "pakkathile", "pakathile",
            "pakkathula enna", "pakathula enna",
            "pakkathula enna places", "pakathula enna places",
            "enakku pakkathula", "en pakkathula",
            "enakku pakathula", "en pakathula",
            "enakku arugula", "en arugula",
            "arugula enna", "arugil enna",
            "inga pakkathula", "inga pakathula",
            "inga arugula", "inga arugil",
            "inga enna places",
            "enna places irukku", "enna places irukku inga"
        )
        private val NEARBY_TANGLISH_REGEX = Regex(
            "\\b(?:(?:enakku|en|inga)\\s+)?(?:pakkathula|pakathula|pakkathile|pakathile)\\b|" +
            "\\b(?:(?:enakku|en|inga)\\s+)?(?:arugula|arugil)(?:\\s+enna)?\\b|" +
            "\\b(?:arugula|arugil)\\s+enna\\b|" +
            "\\b(?:inga\\s+)?enna\\s+places?\\s+(?:irukku|irukkindrathu)(?:\\s+inga)?\\b|" +
            "\\binga\\s+enna\\s+places?\\b|" +
            "\\b(?:pakkathula|pakathula|pakkathile|pakathile)\\s+enna(?:\\s+places?)?\\b",
            RegexOption.IGNORE_CASE
        )

        // ---- Distance / directions ---------------------------------
        private val DIRECTIONS_EN = listOf(
            "how far", "how do i get", "how to get", "how to reach",
            "how do i go", "how to go", "how can i go", "how can i reach",
            "how do i travel", "how to travel", "how can i travel",
            "directions to", "directions from", "directions between", "directions",
            "distance to", "distance from", "distance between",
            "route to", "route from", "way to", "way from", "navigate to", "navigate",
            "which way", "go from", "travel from", "walk from",
            "want to go to", "want to reach", "want to get to", "where is", "to go to"
        )
        private val DIRECTIONS_HI = listOf("कैसे जाएं", "कितनी दूर", "रास्ता")
        private val DIRECTIONS_TA = listOf("எப்படி போவது", "எவ்வளவு தூரம்", "வழி")
        private val DIRECTIONS_TANGLISH = listOf(
            "epdi poganum", "eppadi poganum",
            "epdi poga", "eppadi poga",
            "epdi pogalam", "eppadi pogalam",
            "epdi reach panrathu", "eppadi reach panrathu",
            "epdi reach pandrathu", "eppadi reach pandrathu",
            "epdi porathu", "eppadi porathu"
        )
        private val DIRECTIONS_TANGLISH_REGEX = Regex(
            "\\b(?:e?pdi|eppadi)\\s+(?:poganum|poga|pogalam|porathu|povanum|reach\\s+pan+rathu|reach\\s+pan+drathu)\\b",
            RegexOption.IGNORE_CASE
        )

        // ---- Lost / disoriented ------------------------------------
        private val LOST_EN = listOf(
            "i'm lost", "im lost", "i am lost", "i got lost",
            "where am i", "don't know where i am", "dont know where",
            "help i'm lost", "help im lost", "can't find my way",
            "cant find my way", "disoriented"
        )
        private val LOST_HI = listOf("मैं खो गया", "खो गया हूं", "रास्ता भूल गया")
        private val LOST_TA = listOf("தொலைந்து போனேன்", "வழி தெரியவில்லை")

        // ---- Safety ------------------------------------------------
        private val SAFETY_EN = listOf(
            "is it safe", "is this safe", "safe at night", "safe for women",
            "dangerous", "how safe", "safety tips", "safety tip", "crime", "scam",
            "pickpocket", "theft", "unsafe", "is marina safe", "is it dangerous",
            " safe?", " safe", "is safe"
        )
        private val SAFETY_HI = listOf("सुरक्षित", "खतरनाक", "सुरक्षा")
        private val SAFETY_TA = listOf("பாதுகாப்பானது", "ஆபத்தானது", "பாதுகாப்பு")

        // ---- Travel tips -------------------------------------------
        private val TRAVEL_TIP_EN = listOf(
            "what should i", "how should i", "tips for", "travel tip",
            "travel advice", "advice for", "what to do if", "what happens if",
            "lose my wallet", "lost my wallet", "lost my passport",
            "lose my phone", "lost my phone", "carry cash", "how to carry",
            "keep belongings", "water safe to drink", "should i drink",
            "food safe", "is it safe to eat"
        )

        // ---- Budget travel -----------------------------------------
        private val BUDGET_EN = listOf(
            "rupees", "inr", "budget", "cheap", "affordable", "how much",
            "cost", "price", "free", "entry fee", "ticket price",
            "spend", "low cost", "economical", "cheapest", "value for money",
            "how much does it cost", "how much is"
        )
        private val BUDGET_HI = listOf("रुपये", "बजट", "सस्ता", "कितना खर्च")
        private val BUDGET_TA = listOf("ரூபாய்", "பட்ஜெட்", "மலிவான")

        // ---- Time-constrained planning -----------------------------
        private val TIME_PLAN_EN = listOf(
            "one hour", "1 hour", "two hours", "2 hours", "three hours",
            "half day", "half an hour", "30 minutes", "few hours",
            "in the morning", "in the evening", "in the afternoon",
            "this afternoon", "this morning", "this evening",
            "afternoon plan", "morning plan", "evening plan",
            "what can i visit in", "can i see in"
        )

        // ---- Small talk (supplement SmallTalk.kt) ------------------
        private val SMALL_TALK_EN = listOf(
            "hello", "hi", "hey", "hii", "good morning", "good afternoon",
            "good evening", "good night", "thanks", "thank you", "thx",
            "bye", "goodbye", "see you", "take care", "who are you",
            "what are you", "how are you", "are you a robot", "your name",
            "what can you do", "help me understand", "tell me about yourself"
        )
        private val SMALL_TALK_HI = listOf("नमस्ते", "नमस्कार", "शुक्रिया", "धन्यवाद")
        private val SMALL_TALK_TA = listOf("வணக்கம்", "நன்றி", "சரி")

        /**
         * Deterministic intent classification for travel queries.
         *
         * Priority (highest → lowest):
         *   1. EMERGENCY
         *   2. TRANSPORT_NEARBY / TRANSPORT_ROUTE_SEARCH / TRANSPORT_ROUTE_TIMING
         *      (via existing detectTransportIntent)
         *   3. DESTINATION_TIMING
         *   4. DESTINATION_DETAILS (specific known destination)
         *   5. DESTINATION_DISCOVERY (general "places to visit")
         *   6. ACTIVITY_PLANNING
         *   7. NEARBY_ATTRACTIONS  (requiresLocation=true)
         *   8. DISTANCE_DIRECTIONS (requiresLocation=true)
         *   9. LOST_DISORIENTED    (requiresLocation=true)
         *  10. SAFETY
         *  11. TRAVEL_TIP
         *  12. BUDGET_TRAVEL
         *  13. TIME_CONSTRAINED_PLANNING
         *  14. SMALL_TALK
         *  15. UNKNOWN
         *
         * Rules:
         * - Emergency always wins, regardless of other signals.
         * - Transport beats destination for queries like "Which bus to Marina?".
         * - location-dependent intents (NEARBY_ATTRACTIONS, DISTANCE_DIRECTIONS,
         *   LOST_DISORIENTED) set requiresLocation=true but do NOT activate GPS.
         * - No data is invented; classification only.
         */
        fun classifyTravelIntent(question: String): TravelIntentResult {
            val trimmed = question.trim()
            if (trimmed.isEmpty()) return TravelIntentResult(TravelIntent.UNKNOWN)

            val effectiveQuestion = if (hasAnaphoraReference(trimmed) && lastResolvedDestination != null) {
                resolveAnaphora(trimmed)
            } else {
                trimmed
            }

            val lower = effectiveQuestion.lowercase(Locale.ROOT)
            val normalizedLower = Normalizer.normalize(lower, Normalizer.Form.NFC)

            val hasNearbySignal =
                NEARBY_PROXIMITY_REGEX.containsMatchIn(lower) ||
                NEARBY_HI.any { lower.contains(it) } ||
                NEARBY_TA_REGEX.containsMatchIn(normalizedLower) ||
                NEARBY_TA.any { lower.contains(it) || normalizedLower.contains(it) } ||
                NEARBY_TANGLISH.any { lower.contains(it) } ||
                NEARBY_TANGLISH_REGEX.containsMatchIn(lower)

            // ----------------------------------------------------------
            // 1. EMERGENCY — highest priority, preempts everything
            // ----------------------------------------------------------
            val isEmergency =
                EMERGENCY_EN.any { lower.contains(it) } ||
                EMERGENCY_HI.any { lower.contains(it) } ||
                EMERGENCY_TA.any { lower.contains(it) } ||
                Regex("\\bdanger\\b").containsMatchIn(lower)
            if (isEmergency) {
                return TravelIntentResult(TravelIntent.EMERGENCY)
            }

            // ----------------------------------------------------------
            // 2. LOST_DISORIENTED — distress / lost signal preempts destination and transport
            // ----------------------------------------------------------
            val isLost =
                LOST_EN.any { lower.contains(it) } ||
                LOST_HI.any { lower.contains(it) } ||
                LOST_TA.any { lower.contains(it) } ||
                (Regex("\\blost\\b").containsMatchIn(lower) &&
                 !lower.contains("wallet") &&
                 !lower.contains("passport") &&
                 !lower.contains("phone") &&
                 !lower.contains("bag") &&
                 !lower.contains("card") &&
                 !lower.contains("ticket"))
            if (isLost) {
                return TravelIntentResult(
                    intent = TravelIntent.LOST_DISORIENTED,
                    requiresLocation = true
                )
            }

            // ----------------------------------------------------------
            // 3. TRANSPORT intents via existing detectTransportIntent()
            // ----------------------------------------------------------
            val transportIntent = detectTransportIntent(effectiveQuestion)
            when (transportIntent.subIntent) {
                TransportSubIntent.TRANSPORT_NEARBY ->
                    return TravelIntentResult(
                        intent = TravelIntent.TRANSPORT_NEARBY,
                        requiresLocation = true,
                        requiresTransportData = true,
                        transportIntent = transportIntent
                    )
                TransportSubIntent.TRANSPORT_ROUTE_TIMING ->
                    return TravelIntentResult(
                        intent = TravelIntent.TRANSPORT_ROUTE_TIMING,
                        requiresTransportData = true,
                        transportIntent = transportIntent
                    )
                TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
                TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP ->
                    return TravelIntentResult(
                        intent = TravelIntent.TRANSPORT_ROUTE_SEARCH,
                        requiresTransportData = true,
                        transportIntent = transportIntent
                    )
                TransportSubIntent.NONE -> { /* continue */ }
            }

            // ----------------------------------------------------------
            // 3. TRAVEL_TIP (check before safety to handle "is food safe to eat")
            //    Specific advisory / what-to-do questions.
            // ----------------------------------------------------------
            if (TRAVEL_TIP_EN.any { lower.contains(it) } && !hasNearbySignal) {
                return TravelIntentResult(
                    intent = TravelIntent.TRAVEL_TIP,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 4. DISTANCE_DIRECTIONS (check before destination lookup)
            //    "how far", "how do i get", "how to get", "how to reach", "directions to"
            //    These modify a destination query but are directional, not descriptive.
            // ----------------------------------------------------------
            val hasDirectionSignal =
                DIRECTIONS_EN.any { lower.contains(it) } ||
                DIRECTIONS_HI.any { lower.contains(it) } ||
                DIRECTIONS_TA.any { lower.contains(it) } ||
                DIRECTIONS_TANGLISH.any { lower.contains(it) } ||
                DIRECTIONS_TANGLISH_REGEX.containsMatchIn(lower) ||
                (Regex("\\bfrom\\s+.+\\s+to\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) &&
                 isDestinationQuery(effectiveQuestion)) ||
                Regex("\\b(?:from|at)\\s+.+\\s+(?:and\\s+)?(?:i\\s+)?(?:want\\s+to\\s+)?(?:go|reach|get|travel)\\s+to\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            if (hasDirectionSignal) {
                return TravelIntentResult(
                    intent = TravelIntent.DISTANCE_DIRECTIONS,
                    requiresLocation = true,
                    requiresDestinationData = true
                )
            }

            // ----------------------------------------------------------
            // 5. SAFETY (check before destination lookup)
            //    "is it safe", "is marina safe", "dangerous"
            // ----------------------------------------------------------
            val isSafety =
                SAFETY_EN.any { lower.contains(it) } ||
                SAFETY_HI.any { lower.contains(it) } ||
                SAFETY_TA.any { lower.contains(it) }
            if (isSafety) {
                return TravelIntentResult(
                    intent = TravelIntent.SAFETY,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 6. NEARBY_ATTRACTIONS (non-transport — no transport noun)
            //    requiresLocation=true (classify only, no GPS activation)
            // ----------------------------------------------------------
            val hasTransportNoun = TRANSPORT_MODES.any { lower.contains(it) }
            if (hasNearbySignal && !hasTransportNoun) {
                return TravelIntentResult(
                    intent = TravelIntent.NEARBY_ATTRACTIONS,
                    requiresLocation = true,
                    requiresDestinationData = true
                )
            }

            // ----------------------------------------------------------
            // 5. DESTINATION_TIMING — destination + timing signal
            // ----------------------------------------------------------
            val hasTimingSignal =
                TIMING_EN.any { lower.contains(it) } ||
                TIMING_HI.any { lower.contains(it) } ||
                TIMING_TA.any { lower.contains(it) }

            if (hasTimingSignal && isDestinationQuery(effectiveQuestion)) {
                return TravelIntentResult(
                    intent = TravelIntent.DESTINATION_TIMING,
                    requiresDestinationData = true,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 6. BUDGET_TRAVEL (check before activity and destination lookup)
            //    "rupees", "how much", "entry fee", "cheap", "cost"
            //    Wins over ACTIVITY ("what can I do for 500 rupees?"),
            //    DESTINATION_DETAILS, and DISCOVERY ("cheap places to visit").
            // ----------------------------------------------------------
            val isBudget =
                BUDGET_EN.any { lower.contains(it) } ||
                BUDGET_HI.any { lower.contains(it) } ||
                BUDGET_TA.any { lower.contains(it) }
            if (isBudget) {
                return TravelIntentResult(
                    intent = TravelIntent.BUDGET_TRAVEL,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 7. ACTIVITY_PLANNING (check before destination lookup)
            //    "what can I do", "things to do", "how to enjoy"
            // ----------------------------------------------------------
            if (ACTIVITY_EN.any { lower.contains(it) }) {
                return TravelIntentResult(
                    intent = TravelIntent.ACTIVITY_PLANNING,
                    requiresDestinationData = true,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 8. DESTINATION_DETAILS — specific known destination
            // ----------------------------------------------------------
            if (isDestinationQuery(effectiveQuestion)) {
                return TravelIntentResult(
                    intent = TravelIntent.DESTINATION_DETAILS,
                    requiresDestinationData = true,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 9. DESTINATION_DISCOVERY — general "places to visit"
            // ----------------------------------------------------------
            if (DISCOVERY_EN.any { lower.contains(it) }) {
                return TravelIntentResult(
                    intent = TravelIntent.DESTINATION_DISCOVERY,
                    requiresDestinationData = true,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 12. TIME_CONSTRAINED_PLANNING
            // ----------------------------------------------------------
            if (TIME_PLAN_EN.any { lower.contains(it) }) {
                return TravelIntentResult(
                    intent = TravelIntent.TIME_CONSTRAINED_PLANNING,
                    requiresGemma = true
                )
            }

            // ----------------------------------------------------------
            // 14. SMALL_TALK
            // ----------------------------------------------------------
            val isSmallTalk =
                SMALL_TALK_EN.any { lower.contains(it) } ||
                SMALL_TALK_HI.any { lower.contains(it) } ||
                SMALL_TALK_TA.any { lower.contains(it) }
            if (isSmallTalk) {
                return TravelIntentResult(TravelIntent.SMALL_TALK)
            }

            // ----------------------------------------------------------
            // 15. UNKNOWN fallback
            // ----------------------------------------------------------
            return TravelIntentResult(TravelIntent.UNKNOWN)
        }

        suspend fun processTransportQuery(
            intent: TransportIntent,
            repository: TransportRepository,
            userLat: Double? = null,
            userLon: Double? = null,
            mtcProvider: TransportTimingProvider = AppServices.mtcTransportProvider()
        ): String {
            return when (intent.subIntent) {
                TransportSubIntent.NONE -> ""

                TransportSubIntent.TRANSPORT_ROUTE_TIMING -> {
                    val query = intent.target ?: ""
                    if (query.isBlank()) {
                        return "Based on the available Chennai transport data, please specify a route number for timing information (e.g. 5C, 21G, or 66)."
                    }

                    // Case D: Metro routes are never sent to MTC bus endpoints
                    if (intent.mode == "subway" || query.equals("Blue", ignoreCase = true) || query.equals("Green", ignoreCase = true)) {
                        return "Chennai Metro (CMRL ${query.replaceFirstChar { it.uppercase() }} Line) operates with frequent departures every 5 to 10 minutes between 05:00 and 23:00. Detailed station train schedules are available at CMRL metro stations."
                    }

                    // Priority 1: Official MTC dynamic timing provider
                    val mtcResult = mtcProvider.getRouteTiming(query, intent.origin, intent.destination)
                    when (mtcResult) {
                        is MtcTimingResult.Success -> {
                            val timing = mtcResult.timing
                            val timesStr = timing.times.joinToString(", ")
                            return "Route ${timing.route} from ${timing.origin} to ${timing.destination} has scheduled departures at $timesStr.\n\nThese are scheduled MTC departure timings retrieved from the MTC service and may change in actual operation."
                        }
                        is MtcTimingResult.MultipleOrigins -> {
                            return mtcResult.message
                        }
                        is MtcTimingResult.NoData, is MtcTimingResult.Error -> {
                            // Priority 2: Static GTFS database fallback
                            val staticRes = repository.searchRoutes(query = query)
                            if (staticRes is TransportResult.Success && staticRes.data.isNotEmpty()) {
                                val limit = staticRes.data.take(5)
                                val routesList = limit.joinToString("\n") { r ->
                                    "• Route ${r.routeShortName}: ${r.routeLongName}"
                                }
                                return "I couldn't get the current MTC schedule for route '$query' right now. Based on available static Chennai public transport data, route $query operates along:\n$routesList\n\n(Note: Scheduled timings from MTC were unavailable; showing static route information.)"
                            }

                            return "I couldn't get the current MTC schedule for route '$query' right now. Please verify the route number or check back later."
                        }
                    }
                }

                TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP -> {
                    val target = intent.target ?: "this stop"
                    "Based on the available Chennai transport data, route-to-stop mapping for specific stops like '$target' is not available in the current static dataset. The dataset includes stop locations and route listings, but stop schedules and calling-pattern data are not yet mapped."
                }

                TransportSubIntent.TRANSPORT_NEARBY -> {
                    if (userLat == null || userLon == null) {
                        return "Based on the available Chennai transport data, to find nearby bus stops or metro stations I need your location coordinates. Location coordinates are currently required for proximity searches."
                    }
                    when (val res = repository.getNearbyStops(userLat, userLon, radiusKm = 2.0, mode = intent.mode)) {
                        is TransportResult.Success -> {
                            val stops = res.data
                            if (stops.isEmpty()) {
                                "Based on the available Chennai transport data, no transport stops were found within the selected search radius."
                            } else {
                                val limit = stops.take(5)
                                val stopsList = limit.joinToString("\n") { stop ->
                                    val distStr = "%.3f".format(Locale.ROOT, stop.distanceKm)
                                    val agencyName = stop.agency?.agencyName?.let { " ($it)" } ?: ""
                                    "• ${stop.stopName}$agencyName, about $distStr km away"
                                }
                                val countMsg = if (stops.size > 5) "\n(Showing 5 of ${stops.size} stops found within 2 km radius. Distances are calculated straight-line estimates from your coordinates, not live travel routes or real-time arrivals.)" else "\n(Distances are calculated straight-line estimates from your coordinates, not live travel routes or real-time arrivals.)"
                                "Based on the available Chennai transport data, nearby transport stops include:\n$stopsList$countMsg"
                            }
                        }
                        is TransportResult.Empty -> {
                            "Based on the available Chennai transport data, no transport stops were found within the selected search radius."
                        }
                        is TransportResult.Notice -> {
                            "Based on the available Chennai transport data: ${res.message}"
                        }
                        is TransportResult.NetworkUnavailable -> {
                            "I can't access the transport database right now. Please check your network connection."
                        }
                        is TransportResult.ServerError -> {
                            "The transport database encountered an error (${res.code}). Please try again later."
                        }
                    }
                }

                TransportSubIntent.TRANSPORT_ROUTE_SEARCH -> {
                    val query = intent.target ?: intent.origin ?: intent.destination ?: ""
                    if (query.isBlank() && intent.mode.isNullOrBlank()) {
                        return "Based on the available Chennai transport data, please specify a route number or name to search (e.g. 5C, 21G, or Blue Line)."
                    }
                    val searchQuery = if (!intent.target.isNullOrBlank()) intent.target else null
                    val originParam = normalizeTransitPlace(intent.origin)
                    val destParam = normalizeTransitPlace(intent.destination)
                    when (val res = repository.searchRoutes(
                        query = searchQuery,
                        mode = intent.mode,
                        origin = originParam,
                        destination = destParam
                    )) {
                        is TransportResult.Success -> {
                            val routes = res.data
                            if (routes.isEmpty()) {
                                val targetName = when {
                                    !intent.target.isNullOrBlank() -> "'$query'"
                                    !intent.origin.isNullOrBlank() && !intent.destination.isNullOrBlank() -> "'${intent.origin}' to '${intent.destination}'"
                                    !intent.origin.isNullOrBlank() -> "'${intent.origin}'"
                                    !intent.destination.isNullOrBlank() -> "'${intent.destination}'"
                                    else -> "'$query'"
                                }
                                "Based on the available Chennai transport data, no routes matching $targetName were found."
                            } else {
                                val firstTransfer = routes.firstOrNull()?.transferJourney
                                if (firstTransfer != null) {
                                    val leg1 = firstTransfer.leg1
                                    val leg2 = firstTransfer.leg2
                                    val xferStop = firstTransfer.transferStopName.ifBlank { firstTransfer.transferStopId }

                                    val r1Raw = leg1.routeShortName.trim()
                                    val r1 = if (r1Raw.startsWith("Bus ", ignoreCase = true)) r1Raw.substring(4).trim() else r1Raw
                                    val leg1RouteName = r1.ifBlank { leg1.routeLongName.ifBlank { leg1.routeId } }

                                    val r2Raw = leg2.routeShortName.trim()
                                    val r2 = if (r2Raw.startsWith("Bus ", ignoreCase = true)) r2Raw.substring(4).trim() else r2Raw
                                    val leg2RouteName = r2.ifBlank { leg2.routeLongName.ifBlank { leg2.routeId } }

                                    val b1Stop = leg1.boardStopName.ifBlank { leg1.boardStopId }
                                    val a1Stop = leg1.alightStopName.ifBlank { leg1.alightStopId }
                                    val a2Stop = leg2.alightStopName.ifBlank { leg2.alightStopId }

                                    "No direct bus was found. You can take a 2-bus route:\n\n" +
                                        "1. Board Bus $leg1RouteName at $b1Stop and get down at $a1Stop.\n" +
                                        "2. At $xferStop, change to Bus $leg2RouteName and get down at $a2Stop."
                                } else {
                                    val firstRoute = routes.first()
                                    val isMetroOrSubway = firstRoute.routeType == 1 ||
                                        intent.mode.equals("subway", ignoreCase = true) ||
                                        intent.mode.equals("metro", ignoreCase = true)
                                    val hasBoardAndAlight = !firstRoute.boardStopName.isNullOrBlank() &&
                                        !firstRoute.alightStopName.isNullOrBlank()

                                    if (!isMetroOrSubway && firstRoute.routeType == 3 && hasBoardAndAlight) {
                                        val dirSuffix = when {
                                            !firstRoute.headsign.isNullOrBlank() -> " toward ${firstRoute.headsign.trim()}"
                                            firstRoute.routeLongName.contains(Regex("\\s+(?:TO|to)\\s+")) -> {
                                                val parts = firstRoute.routeLongName.split(Regex("\\s+(?:TO|to)\\s+"))
                                                if (parts.size == 2 && parts[1].isNotBlank()) " toward ${parts[1].trim()}" else ""
                                            }
                                            else -> ""
                                        }
                                        val rRaw = firstRoute.routeShortName.trim()
                                        val rClean = if (rRaw.startsWith("Bus ", ignoreCase = true)) rRaw.substring(4).trim() else rRaw
                                        val busName = rClean.ifBlank { firstRoute.routeLongName.ifBlank { firstRoute.routeId } }
                                        val boardStop = firstRoute.boardStopName!!.trim().trimEnd('.')
                                        val alightStop = firstRoute.alightStopName!!.trim().trimEnd('.')

                                        "You can take Bus $busName$dirSuffix.\n\n" +
                                            "Board at $boardStop\n" +
                                            "and get down at $alightStop."
                                    } else {
                                        val limit = routes.take(5)
                                        val routesList = limit.joinToString("\n") { r ->
                                            val modeStr = when (r.routeType) {
                                                1 -> "Metro"
                                                2 -> "Rail"
                                                3 -> "Bus"
                                                else -> "Transit"
                                            }
                                            val agencyStr = r.agencyName?.let { " - $it" } ?: ""
                                            "• Route ${r.routeShortName}: ${r.routeLongName} [$modeStr]$agencyStr"
                                        }
                                        val moreNote = if (routes.size > 5) {
                                            "\n(Showing 5 of ${routes.size} variants found in the static schedule database. Note: Schedules and real-time arrivals are not included in this static dataset.)"
                                        } else {
                                            "\n(Static route listing from Chennai public transport data. Note: Live tracking and real-time ETAs are not included.)"
                                        }
                                        val heading = when {
                                            !intent.target.isNullOrBlank() -> "matching routes for '$query':"
                                            !intent.origin.isNullOrBlank() && !intent.destination.isNullOrBlank() ->
                                                "verified direct routes from ${intent.origin} to ${intent.destination}:"
                                            !intent.origin.isNullOrBlank() -> "matching routes serving '${intent.origin}':"
                                            !intent.destination.isNullOrBlank() -> "matching routes serving '${intent.destination}':"
                                            intent.mode != null -> "matching ${intent.mode} routes:"
                                            else -> "matching routes for '$query':"
                                        }
                                        "Based on the available Chennai transport data, $heading\n$routesList$moreNote"
                                    }
                                }
                            }
                        }
                        is TransportResult.Empty -> {
                            val targetName = when {
                                !intent.target.isNullOrBlank() -> "'$query'"
                                !intent.origin.isNullOrBlank() && !intent.destination.isNullOrBlank() -> "'${intent.origin}' to '${intent.destination}'"
                                !intent.origin.isNullOrBlank() -> "'${intent.origin}'"
                                !intent.destination.isNullOrBlank() -> "'${intent.destination}'"
                                else -> "'$query'"
                            }
                            "Based on the available Chennai transport data, no routes matching $targetName were found."
                        }
                        is TransportResult.Notice -> {
                            "Based on the available Chennai transport data: ${res.message}"
                        }
                        is TransportResult.NetworkUnavailable -> {
                            "I can't access the transport database right now. Please check your network connection."
                        }
                        is TransportResult.ServerError -> {
                            "The transport database encountered an error (${res.code}). Please try again later."
                        }
                    }
                }
            }
        }
    }
}
