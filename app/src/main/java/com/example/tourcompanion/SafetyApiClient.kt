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

class SafetyApiClient(private val baseUrl: String = AppConfig.BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendCheckIn(userId: Long, status: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl + "safety-checkin.php"
            val bodyJson = JSONObject().apply {
                put("user_id", userId)
                put("status", status)
            }
            Log.d("SafetyApiClient", "CHECKIN: sending $status for user $userId to $url")

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("SafetyApiClient", "CHECKIN: HTTP ${response.code}")
                val text = response.body?.string() ?: return@withContext null
                Log.d("SafetyApiClient", "CHECKIN response body: $text")
                if (!response.isSuccessful) {
                    return@withContext null
                }
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w("SafetyApiClient", "CHECKIN: request failed", e)
            null
        }
    }

    suspend fun getLatestStatus(userId: Long): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl + "safety-status.php"
            val bodyJson = JSONObject().apply {
                put("user_id", userId)
            }
            Log.d("SafetyApiClient", "STATUS: fetching latest status for user $userId from $url")

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("SafetyApiClient", "STATUS: HTTP ${response.code}")
                val text = response.body?.string() ?: return@withContext null
                Log.d("SafetyApiClient", "STATUS response body: $text")
                if (!response.isSuccessful) {
                    return@withContext null
                }
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w("SafetyApiClient", "STATUS: request failed", e)
            null
        }
    }
}

