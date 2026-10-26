package com.example.tourcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks your own backend for an answer. The backend (not the app) holds the Claude API key.
 * Returns null on any problem so the caller can fall back gracefully.
 */
class AiAnswerClient(private val endpoint: String) {

    val isConfigured: Boolean
        get() = endpoint.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun ask(question: String): String? = withContext(Dispatchers.IO) {

        if (!isConfigured) return@withContext null

        try {
            val body = JSONObject()
                .put("question", question)
                .toString()
                .toRequestBody(jsonType)

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val text = response.body?.string() ?: return@use null

                JSONObject(text)
                    .optString("answer")
                    .trim()
                    .ifEmpty { null }
            }
        } catch (e: Exception) {
            null
        }
    }
}
