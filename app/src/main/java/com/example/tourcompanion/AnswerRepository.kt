package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One place that decides how a question is answered:
 *   1. Explicit transport intent: static Chennai public transport data via TransportRepository
 *   2. Backend answer first when available (ask.php connected to MySQL travel_tips, destinations, query_logs)
 *   3. Existing SmallTalk responses where appropriate (greetings, thanks, who are you)
 *   4. Local SQLite/KnowledgeBase fallback when the backend is unavailable or returns no specific tip
 *   5. Graceful offline behavior
 */
class AnswerRepository(
    context: Context,
    private val transport: TransportRepository = AppServices.transportRepository(context.applicationContext),
    private val remote: AiAnswerClient = AiAnswerClient(AppConfig.AI_PROXY_URL),
    private val local: QueryProcessor = AppServices.queryProcessor(context.applicationContext)
) {

    enum class Source { BACKEND, LOCAL, SMALL_TALK, NONE, TRANSPORT }

    data class Answer(val text: String, val source: Source)

    private val appContext = context.applicationContext

    suspend fun answer(question: String, userLat: Double? = null, userLon: Double? = null): Answer {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            return Answer(appContext.getString(R.string.type_question_first), Source.NONE)
        }

        // 1. Explicit Transport Intent (Deterministic, static GTFS data)
        val transportIntent = local.detectTransportIntent(trimmed)
        if (transportIntent.subIntent != TransportSubIntent.NONE) {
            val transportAnswer = local.processTransportQuery(
                intent = transportIntent,
                repository = transport,
                userLat = userLat,
                userLon = userLon
            )
            return Answer(transportAnswer, Source.TRANSPORT)
        }

        val userId = if (Session.isLoggedIn(appContext)) Session.userId(appContext) else 0L

        // 2. Backend answer first when available (records query in MySQL query_logs)
        val backendResult = remote.ask(trimmed, userId)
        if (backendResult != null) {
            Log.d("AnswerRepository", "Backend returned: topic=${backendResult.topic}, specific=${backendResult.isSpecificTip}")

            // If backend found a matching travel tip, return it directly
            if (backendResult.isSpecificTip) {
                return Answer(backendResult.answer, Source.BACKEND)
            }

            // If backend had no specific tip, check SmallTalk (e.g. "hi", "who are you")
            SmallTalk.reply(trimmed)?.let {
                return Answer(it, Source.SMALL_TALK)
            }

            // Check local SQLite as fallback
            val localTip = withContext(Dispatchers.Default) {
                local.processQuery(trimmed)
            }
            if (localTip != null) {
                return Answer(localTip.answer, Source.LOCAL)
            }

            // Return backend's general answer ("Sorry, I don't have a specific travel tip...")
            return Answer(backendResult.answer, Source.BACKEND)
        }

        // 3. Local fallback when backend is unavailable (offline, network error, timeout):
        Log.d("AnswerRepository", "Backend unavailable; using local fallback for '$trimmed'")

        // SmallTalk
        SmallTalk.reply(trimmed)?.let {
            return Answer(it, Source.SMALL_TALK)
        }

        // Local SQLite travel tips database
        val localTip = withContext(Dispatchers.Default) {
            local.processQuery(trimmed)
        }
        if (localTip != null) {
            return Answer(localTip.answer, Source.LOCAL)
        }

        // Friendly no answer message
        return Answer(appContext.getString(R.string.no_answer), Source.NONE)
    }
}
