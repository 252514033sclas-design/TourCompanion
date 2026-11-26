package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class BackendAnswer(
    val question: String,
    val topic: String?,
    val answer: String,
    val isSpecificTip: Boolean,
    val isAiGenerated: Boolean = false
)

/**
 * Communicates with the PHP backend (ask.php).
 * Returns null on network errors, timeouts, or invalid responses to trigger fallback.
 */
class AiAnswerClient(
    private val endpoint: String = AppConfig.AI_PROXY_URL,
    nearbyUrl: String? = null,
    distanceUrl: String? = null
) {

    val isConfigured: Boolean
        get() = endpoint.isNotBlank()

    private val nearbyEndpoint: String = nearbyUrl ?: if (endpoint.contains("ask.php")) {
        endpoint.replace("ask.php", "destinations-nearby.php")
    } else {
        AppConfig.DESTINATIONS_NEARBY_URL
    }

    private val distanceEndpoint: String = distanceUrl ?: if (endpoint.contains("ask.php")) {
        endpoint.replace("ask.php", "destinations-distance.php")
    } else {
        AppConfig.DESTINATIONS_DISTANCE_URL
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    internal fun buildAskRequestBody(
        question: String,
        userId: Long = 0L,
        userLat: Double? = null,
        userLon: Double? = null,
        conversationHistory: List<ConversationMessage> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("question", question.trim())
        if (userId > 0L) {
            put("user_id", userId)
        }
        if (userLat != null && userLon != null) {
            put("latitude", userLat)
            put("longitude", userLon)
        }
        val historyArray = JSONArray()
        for (msg in conversationHistory) {
            historyArray.put(
                JSONObject().apply {
                    put("role", if (msg.role == ChatRole.USER) "user" else "assistant")
                    put("text", msg.text)
                }
            )
        }
        put("conversation_history", historyArray)
    }

    suspend fun ask(
        question: String,
        userId: Long = 0L,
        userLat: Double? = null,
        userLon: Double? = null,
        conversationHistory: List<ConversationMessage> = emptyList()
    ): BackendAnswer? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        val trimmed = question.trim()
        if (trimmed.isEmpty()) return@withContext null

        try {
            val bodyJson = buildAskRequestBody(
                question = trimmed,
                userId = userId,
                userLat = userLat,
                userLon = userLon,
                conversationHistory = conversationHistory
            )

            Log.d("AiAnswerClient", "ASK: sending question '$trimmed' (user_id=$userId) to $endpoint")

            val request = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("AiAnswerClient", "ASK: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    return@use null
                }

                val text = response.body?.string() ?: return@use null
                Log.d("AiAnswerClient", "ASK response: $text")

                val json = JSONObject(text)
                if (json.optString("status") != "ok") {
                    return@use null
                }

                val answer = json.optString("answer").trim()
                if (answer.isEmpty()) return@use null

                val topic = if (json.has("topic") && !json.isNull("topic")) {
                    json.optString("topic").trim().ifEmpty { null }
                } else null

                val isAi = json.optBoolean("ai_generated", false)

                BackendAnswer(
                    question = json.optString("question", trimmed),
                    topic = topic,
                    answer = answer,
                    isSpecificTip = topic != null,
                    isAiGenerated = isAi
                )
            }
        } catch (e: Exception) {
            Log.w("AiAnswerClient", "ASK: network or parse failure", e)
            null
        }
    }

    suspend fun askNearby(
        question: String,
        userLat: Double,
        userLon: Double,
        radiusKm: Double = 15.0
    ): BackendAnswer? = withContext(Dispatchers.IO) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return@withContext null

        try {
            val bodyJson = JSONObject().apply {
                put("question", trimmed)
                put("latitude", userLat)
                put("longitude", userLon)
                put("radius_km", radiusKm)
            }

            Log.d("AiAnswerClient", "ASK_NEARBY: sending '$trimmed' ($userLat, $userLon) to $nearbyEndpoint")

            val request = Request.Builder()
                .url(nearbyEndpoint)
                .post(bodyJson.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("AiAnswerClient", "ASK_NEARBY: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    return@use null
                }

                val text = response.body?.string() ?: return@use null
                Log.d("AiAnswerClient", "ASK_NEARBY response: $text")

                val json = JSONObject(text)
                if (json.optString("status") != "ok") {
                    return@use null
                }

                val answer = json.optString("answer").trim()
                if (answer.isEmpty()) return@use null

                val topic = if (json.has("topic") && !json.isNull("topic")) {
                    json.optString("topic").trim().ifEmpty { "Nearby Attractions" }
                } else "Nearby Attractions"

                val isAi = json.optBoolean("ai_generated", false)

                BackendAnswer(
                    question = json.optString("question", trimmed),
                    topic = topic,
                    answer = answer,
                    isSpecificTip = true,
                    isAiGenerated = isAi
                )
            }
        } catch (e: Exception) {
            Log.w("AiAnswerClient", "ASK_NEARBY: network or parse failure", e)
            null
        }
    }

    suspend fun askDistance(
        question: String,
        userLat: Double,
        userLon: Double
    ): BackendAnswer? = withContext(Dispatchers.IO) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return@withContext null

        try {
            val httpUrl = distanceEndpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
                addQueryParameter("lat", userLat.toString())
                addQueryParameter("lon", userLon.toString())
                addQueryParameter("q", trimmed)
            }?.build() ?: return@withContext null

            Log.d("AiAnswerClient", "ASK_DISTANCE: sending '$trimmed' ($userLat, $userLon) to $httpUrl")

            val request = Request.Builder()
                .url(httpUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("AiAnswerClient", "ASK_DISTANCE: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    return@use null
                }

                val text = response.body?.string() ?: return@use null
                Log.d("AiAnswerClient", "ASK_DISTANCE response: $text")

                val json = JSONObject(text)
                val status = json.optString("status")
                if (status != "ok" && status != "no_result") {
                    return@use null
                }

                val answer = json.optString("answer").trim()
                if (answer.isEmpty()) return@use null

                val topic = if (json.has("destination") && !json.isNull("destination")) {
                    json.optString("destination").trim()
                } else if (json.has("name") && !json.isNull("name")) {
                    json.optString("name").trim()
                } else "Destination Distance"

                val isAi = json.optBoolean("ai_generated", false)

                BackendAnswer(
                    question = json.optString("question", trimmed),
                    topic = topic,
                    answer = answer,
                    isSpecificTip = true,
                    isAiGenerated = isAi
                )
            }
        } catch (e: Exception) {
            Log.w("AiAnswerClient", "ASK_DISTANCE: network or parse failure", e)
            null
        }
    }
}
