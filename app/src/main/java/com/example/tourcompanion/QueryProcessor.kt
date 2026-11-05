package com.example.tourcompanion

import android.content.Context
import java.util.Locale

enum class TransportSubIntent {
    NONE,
    TRANSPORT_NEARBY,
    TRANSPORT_ROUTE_SEARCH,
    TRANSPORT_STOP_ROUTE_LOOKUP
}

data class TransportIntent(
    val subIntent: TransportSubIntent,
    val target: String? = null,
    val mode: String? = null
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

    fun warmUp() {
        check(getMatcher().size > 0) { "travel_tips table is empty" }
    }

    fun reload() {
        synchronized(this) {
            matcher = TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP)
        }
    }

    fun processQuery(question: String): TravelTip? = getMatcher().match(question)

    fun detectTransportIntent(question: String): TransportIntent = Companion.detectTransportIntent(question)

    suspend fun processTransportQuery(
        intent: TransportIntent,
        repository: TransportRepository,
        userLat: Double? = null,
        userLon: Double? = null
    ): String = Companion.processTransportQuery(intent, repository, userLat, userLon)

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

        private val EXPLICIT_ROUTE_PREFIX_REGEX = Regex(
            "\\b(?:route|bus|bus\\s+no\\.?|bus\\s+number)\\s+([0-9a-zA-Z\\-]+)\\b",
            RegexOption.IGNORE_CASE
        )

        private val STANDALONE_ROUTE_CODE_QUERY_REGEX = Regex(
            "^(?:what is|tell me about|info on|details for|about)?\\s*(?:the\\s+)?([0-9]{1,4}[A-Za-z]{1,3}|[A-Za-z]{1,3}[0-9]{1,4}[A-Za-z]?)\\??$",
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

            // 4. ROUTE SEARCH
            // Metro line check (e.g. "Blue Line metro", "Tell me about the Blue Line", "Green Line")
            val metroMatch = METRO_LINE_REGEX.find(trimmed)
            if (metroMatch != null) {
                val line = metroMatch.groupValues[1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                return TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = line, mode = "subway")
            }

            // Explicit "route <id>" or "bus <id>" (e.g. "What is route 21G?", "Tell me about route 5C")
            val explicitRouteMatch = EXPLICIT_ROUTE_PREFIX_REGEX.find(trimmed)
            if (explicitRouteMatch != null) {
                val target = explicitRouteMatch.groupValues[1].trim()
                if (target.isNotEmpty() && !target.equals("beach", ignoreCase = true) && !target.equals("temple", ignoreCase = true)) {
                    return TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = target)
                }
            }

            // Standalone route code with query (e.g. "What is 5C?", "Tell me about 21G", "5C")
            val standaloneMatch = STANDALONE_ROUTE_CODE_QUERY_REGEX.find(trimmed)
            if (standaloneMatch != null) {
                val target = standaloneMatch.groupValues[1].trim()
                if (target.isNotEmpty()) {
                    return TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = target)
                }
            }

            return TransportIntent(TransportSubIntent.NONE)
        }

        suspend fun processTransportQuery(
            intent: TransportIntent,
            repository: TransportRepository,
            userLat: Double? = null,
            userLon: Double? = null
        ): String {
            return when (intent.subIntent) {
                TransportSubIntent.NONE -> ""

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
                    val query = intent.target ?: ""
                    if (query.isBlank()) {
                        return "Based on the available Chennai transport data, please specify a route number or name to search (e.g. 5C, 21G, or Blue Line)."
                    }
                    when (val res = repository.searchRoutes(query = query, mode = intent.mode)) {
                        is TransportResult.Success -> {
                            val routes = res.data
                            if (routes.isEmpty()) {
                                "Based on the available Chennai transport data, no routes matching '$query' were found."
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
                                "Based on the available Chennai transport data, matching routes for '$query':\n$routesList$moreNote"
                            }
                        }
                        is TransportResult.Empty -> {
                            "Based on the available Chennai transport data, no routes matching '$query' were found."
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
