package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One place that decides how a question is answered using intent-driven routing:
 *   1. Emergency (highest priority, deterministic 112 guidance, zero AI call)
 *   2. Explicit transport intent (static Chennai GTFS + dynamic MTC scheduled timing)
 *   3. Small talk (instant local response, no network overhead)
 *   4. Location-dependent intents (NEARBY_ATTRACTIONS, DISTANCE_DIRECTIONS, LOST_DISORIENTED - Phase 5 flags only)
 *   5. Destination & knowledge queries via backend query.php (MySQL verified facts + local Gemma synthesis)
 *   6. Local SQLite / KnowledgeBase fallback when backend is unavailable
 *   7. Graceful offline behavior
 */
class AnswerRepository(
    context: Context,
    private val transport: TransportRepository = AppServices.transportRepository(context.applicationContext),
    private val remote: AiAnswerClient = AiAnswerClient(AppConfig.QUERY_URL),
    private val local: QueryProcessor = AppServices.queryProcessor(context.applicationContext),
    private val location: AppLocationManager = AppServices.locationProvider(context.applicationContext)
) {

    companion object {
        const val DESTINATION_OFFLINE_MESSAGE = "Destination details require a connection to the travel database."
        const val EMERGENCY_RESPONSE = "If you are facing an emergency in India, call 112 or seek help from nearby authorities or trusted people."
        const val NEARBY_ATTRACTIONS_UNAVAILABLE = "To find attractions near you, Semmozhi needs your location. Please grant location access to see nearby places."
        const val DISTANCE_DIRECTIONS_UNAVAILABLE = "Distance and direction calculations from your current location are not yet available."
        const val DISTANCE_LOCATION_REQUIRED_MESSAGE = "To calculate the distance to a destination, Semmozhi needs your location. Please grant location access."
        const val DISTANCE_OFFLINE_MESSAGE = "Distance information requires a connection to the travel database."
        const val LOST_DISORIENTED_MESSAGE = "If you are lost or disoriented, please stay in a safe, well-lit area or call 112 for emergency help. Location-based navigation is not yet available."

        const val LOCATION_DISABLED_MESSAGE = "Device location is turned off. Please enable location services on your device to continue."
        const val LOCATION_FIX_UNAVAILABLE_MESSAGE = "Semmozhi is unable to get your current location right now. Please ensure you have a clear GPS signal and try again."

        fun namedOriginRoutingLimitationMessage(targetName: String, originName: String): String =
            "I can identify $targetName, but Semmozhi can't calculate a route from $originName yet. Your phone's current location is used only for distance estimates."
    }

    enum class Source { BACKEND, LOCAL, SMALL_TALK, NONE, TRANSPORT }

    data class Answer(val text: String, val source: Source)

    private val appContext = context.applicationContext

    suspend fun answer(
        question: String,
        userLat: Double? = null,
        userLon: Double? = null,
        conversationHistory: List<ConversationMessage> = emptyList()
    ): Answer {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            return Answer(getStringSafe(R.string.type_question_first, "Please type a question first."), Source.NONE)
        }

        // Contextual Destination Memory / Session Anaphora Resolution:
        val hasAnaphora = local.hasAnaphoraReference(trimmed)
        val beforeMemory = QueryProcessor.lastResolvedDestination?.canonicalName
        val effectiveQuestion = local.resolveAnaphora(trimmed)

        // Deterministic Intent Classification
        val classification = local.classifyTravelIntent(effectiveQuestion)
        val resolvedDest = local.resolveDestination(effectiveQuestion)
        val afterMemory = QueryProcessor.lastResolvedDestination?.canonicalName
        val usedRemembered = hasAnaphora && beforeMemory != null && resolvedDest?.canonicalName == beforeMemory

        Log.i("DestinationMemoryTrace", "Query: '$trimmed' | Effective: '$effectiveQuestion' | Intent: ${classification.intent} | ResolvedDest: ${resolvedDest?.canonicalName} | UsedRemembered: $usedRemembered | Memory: $afterMemory")

        // 1. EMERGENCY (highest priority — pre-empts all handlers, zero AI call, works 100% offline)
        if (classification.intent == TravelIntent.EMERGENCY) {
            val emergencyText = try {
                withContext(Dispatchers.Default) {
                    local.processQuery("emergency")?.answer
                } ?: EMERGENCY_RESPONSE
            } catch (_: Exception) {
                EMERGENCY_RESPONSE
            }
            return Answer(emergencyText, Source.LOCAL)
        }

        // 2. TRANSPORT INTENTS (Deterministic transit GTFS / dynamic MTC scheduled timing)
        if (classification.intent == TravelIntent.TRANSPORT_NEARBY ||
            classification.intent == TravelIntent.TRANSPORT_ROUTE_SEARCH ||
            classification.intent == TravelIntent.TRANSPORT_ROUTE_TIMING
        ) {
            val transportIntent = classification.transportIntent ?: local.detectTransportIntent(trimmed)
            val transportAnswer = local.processTransportQuery(
                intent = transportIntent,
                repository = transport,
                userLat = userLat,
                userLon = userLon
            )
            return Answer(transportAnswer, Source.TRANSPORT)
        }

        // 3. SMALL TALK (Friendly conversation handled locally without network overhead)
        if (classification.intent == TravelIntent.SMALL_TALK) {
            val reply = SmallTalk.reply(trimmed)
                ?: "Hello! I'm Semmozhi, your travel companion. How can I help you today?"
            return Answer(reply, Source.SMALL_TALK)
        }

        // 4. LOCATION-DEPENDENT INTENTS
        when (classification.intent) {
            TravelIntent.NEARBY_ATTRACTIONS -> {
                var lat = userLat
                var lon = userLon
                if ((lat == null || lon == null) && location.hasLocationPermission() && location.isLocationEnabled()) {
                    val fresh = location.getFreshCoordinates() ?: location.getLastKnownCoordinates()
                    if (fresh != null) {
                        lat = fresh.first
                        lon = fresh.second
                    }
                }
                if (lat == null || lon == null) {
                    val message = when {
                        !location.hasLocationPermission() -> NEARBY_ATTRACTIONS_UNAVAILABLE
                        location.isLocationManagerAvailable() && !location.isLocationEnabled() -> LOCATION_DISABLED_MESSAGE
                        location.isLocationManagerAvailable() -> LOCATION_FIX_UNAVAILABLE_MESSAGE
                        else -> NEARBY_ATTRACTIONS_UNAVAILABLE
                    }
                    return Answer(message, Source.LOCAL)
                }
                // Phase 6A: One-time current-location resolution -> destinations-nearby.php
                val nearbyResult = remote.askNearby(trimmed, lat, lon)
                if (nearbyResult != null && nearbyResult.answer.isNotBlank()) {
                    return Answer(nearbyResult.answer, Source.BACKEND)
                }
                return Answer(
                    "Unable to connect to the nearby attractions service right now. Please check your network connection.",
                    Source.LOCAL
                )
            }
            TravelIntent.DISTANCE_DIRECTIONS -> {
                // Priority 5A: Transparent limitation for named-origin -> named-destination routing
                val compoundResult = local.resolveCompoundDestinations(effectiveQuestion)
                    ?: QueryProcessor.aliasMatcher?.resolveCompoundDestinations(effectiveQuestion)

                if (compoundResult != null && compoundResult.origin != null) {
                    val originName = compoundResult.origin.canonicalName
                    val targetName = compoundResult.target.canonicalName
                    return Answer(
                        namedOriginRoutingLimitationMessage(targetName, originName),
                        Source.LOCAL
                    )
                }

                // Resolve target destination before requesting GPS or hitting network
                val resolvedTarget = compoundResult?.target
                    ?: local.resolveDestination(effectiveQuestion)
                    ?: QueryProcessor.aliasMatcher?.resolveDestination(effectiveQuestion)

                if (resolvedTarget == null) {
                    val candidate = QueryProcessor.extractCandidateTarget(effectiveQuestion)
                    val targetDisplay = if (!candidate.isNullOrBlank()) candidate else effectiveQuestion
                    return Answer(
                        "I could not find a verified Chennai destination matching '$targetDisplay'.",
                        Source.LOCAL
                    )
                }

                var lat = userLat
                var lon = userLon
                if ((lat == null || lon == null) && location.hasLocationPermission() && location.isLocationEnabled()) {
                    val fresh = location.getFreshCoordinates() ?: location.getLastKnownCoordinates()
                    if (fresh != null) {
                        lat = fresh.first
                        lon = fresh.second
                    }
                }
                if (lat == null || lon == null) {
                    val message = when {
                        !location.hasLocationPermission() -> DISTANCE_LOCATION_REQUIRED_MESSAGE
                        location.isLocationManagerAvailable() && !location.isLocationEnabled() -> LOCATION_DISABLED_MESSAGE
                        location.isLocationManagerAvailable() -> LOCATION_FIX_UNAVAILABLE_MESSAGE
                        else -> DISTANCE_LOCATION_REQUIRED_MESSAGE
                    }
                    return Answer(message, Source.LOCAL)
                }
                // Phase 6B: Distance from current location -> destinations-distance.php
                val targetQuery = resolvedTarget.canonicalName
                val distanceResult = remote.askDistance(targetQuery, lat, lon)
                if (distanceResult != null && distanceResult.answer.isNotBlank()) {
                    return Answer(distanceResult.answer, Source.BACKEND)
                }
                return Answer(DISTANCE_OFFLINE_MESSAGE, Source.LOCAL)
            }
            TravelIntent.LOST_DISORIENTED -> {
                return Answer(LOST_DISORIENTED_MESSAGE, Source.LOCAL)
            }
            else -> { /* Proceed to backend / knowledge query routing */ }
        }

        // Priority 5A double safety: prevent named-origin routing from leaking to general knowledge
        val compoundCheck = local.resolveCompoundDestinations(effectiveQuestion)
            ?: QueryProcessor.aliasMatcher?.resolveCompoundDestinations(effectiveQuestion)
        if (compoundCheck != null && compoundCheck.origin != null) {
            val lower = effectiveQuestion.lowercase(Locale.ROOT)
            val isRoutingQuery = lower.contains("get to") || lower.contains("go to") ||
                lower.contains("reach") || lower.contains("directions") ||
                lower.contains("from") || lower.contains("to") ||
                lower.contains("route") || lower.contains("way")
            if (isRoutingQuery) {
                return Answer(
                    namedOriginRoutingLimitationMessage(
                        compoundCheck.target.canonicalName,
                        compoundCheck.origin.canonicalName
                    ),
                    Source.LOCAL
                )
            }
        }

        // 5. DESTINATION, TRAVEL TIPS, BUDGET, SAFETY, AND GENERAL QUERIES
        val userId = try {
            if (Session.isLoggedIn(appContext)) Session.userId(appContext) else 0L
        } catch (_: Exception) {
            0L
        }

        // Backend answer first when available (query.php connected to verified MySQL destinations/facts + Gemma synthesis)
        val backendResult = remote.ask(effectiveQuestion, userId, conversationHistory = conversationHistory)
        if (backendResult != null) {
            Log.d("AnswerRepository", "Backend returned: topic=${backendResult.topic}, specific=${backendResult.isSpecificTip}, ai=${backendResult.isAiGenerated}")

            // AI answer is PRIMARY: return directly if AI synthesized or specific verified tip
            if (backendResult.isAiGenerated || backendResult.isSpecificTip) {
                return Answer(backendResult.answer, Source.BACKEND)
            }

            // If backend had no specific tip, check SmallTalk fallback
            SmallTalk.reply(effectiveQuestion)?.let {
                return Answer(it, Source.SMALL_TALK)
            }

            // If query is about a known destination or requires destination data,
            // prevent misleading travel-tip fallback
            if (local.isDestinationQuery(effectiveQuestion) || classification.requiresDestinationData) {
                return Answer(DESTINATION_OFFLINE_MESSAGE, Source.LOCAL)
            }

            // Check local SQLite as fallback
            val localTip = withContext(Dispatchers.Default) {
                local.processQuery(effectiveQuestion)
            }
            if (localTip != null) {
                return Answer(localTip.answer, Source.LOCAL)
            }

            // Return backend's general answer
            return Answer(backendResult.answer, Source.BACKEND)
        }

        // 6. LOCAL FALLBACK when backend is unavailable (offline, network error, timeout)
        Log.d("AnswerRepository", "Backend unavailable; using local fallback for '$effectiveQuestion'")

        // SmallTalk fallback
        SmallTalk.reply(effectiveQuestion)?.let {
            return Answer(it, Source.SMALL_TALK)
        }

        // Destination offline protection
        if (local.isDestinationQuery(effectiveQuestion) || classification.requiresDestinationData) {
            return Answer(DESTINATION_OFFLINE_MESSAGE, Source.LOCAL)
        }

        // Local SQLite travel tips database
        val localTip = withContext(Dispatchers.Default) {
            local.processQuery(effectiveQuestion)
        }
        if (localTip != null) {
            return Answer(localTip.answer, Source.LOCAL)
        }

        // Friendly no answer fallback
        val noAnswer = getStringSafe(
            R.string.no_answer,
            "I don't have an answer for that yet. Try asking about safety, transport, money, phrases, food, hotels or emergencies. In an emergency, call 112."
        )
        return Answer(noAnswer, Source.NONE)
    }

    private fun getStringSafe(resId: Int, defaultStr: String): String {
        return try {
            appContext.getString(resId) ?: defaultStr
        } catch (_: Exception) {
            defaultStr
        }
    }
}
