package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class BackendAnswer(
    val question: String,
    val topic: String?,
    val answer: String,
    val isSpecificTip: Boolean
)

/**
 * Communicates with the PHP backend (ask.php).
 * Returns null on network errors, timeouts, or invalid responses to trigger fallback.
 */
class AiAnswerClient(private val endpoint: String = AppConfig.AI_PROXY_URL) {

    val isConfigured: Boolean
        get() = endpoint.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun ask(question: String, userId: Long = 0L): BackendAnswer? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        val trimmed = question.trim()
        if (trimmed.isEmpty()) return@withContext null

        try {
            val bodyJson = JSONObject().apply {
                put("question", trimmed)
                if (userId > 0L) {
                    put("user_id", userId)
                }
            }

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

                BackendAnswer(
                    question = json.optString("question", trimmed),
                    topic = topic,
                    answer = answer,
                    isSpecificTip = topic != null
                )
            }
        } catch (e: Exception) {
            Log.w("AiAnswerClient", "ASK: network or parse failure", e)
            null
        }
    }
}
