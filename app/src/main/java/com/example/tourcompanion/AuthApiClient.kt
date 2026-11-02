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

class AuthApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType =
        "application/json; charset=utf-8".toMediaType()

    private val baseUrl =
        AppConfig.BASE_URL

    suspend fun login(
        email: String,
        password: String
    ): JSONObject? = withContext(Dispatchers.IO) {

        try {
            val json = JSONObject()
                .put("email", email)
                .put("password", password)

            Log.d(
                "AuthApiClient",
                "LOGIN: sending request to ${baseUrl}login.php"
            )

            val request = Request.Builder()
                .url(baseUrl + "login.php")
                .post(json.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->

                Log.d(
                    "AuthApiClient",
                    "LOGIN: HTTP ${response.code}"
                )

                val text = response.body?.string()

                Log.d(
                    "AuthApiClient",
                    "LOGIN: response body = $text"
                )

                if (!response.isSuccessful || text == null) {
                    return@withContext null
                }

                JSONObject(text)
            }

        } catch (e: Exception) {

            Log.e(
                "AuthApiClient",
                "LOGIN: request failed",
                e
            )

            null
        }
    }

    suspend fun register(
        name: String,
        email: String,
        password: String
    ): JSONObject? = withContext(Dispatchers.IO) {

        try {
            val json = JSONObject()
                .put("name", name)
                .put("email", email)
                .put("password", password)

            Log.d(
                "AuthApiClient",
                "REGISTER: sending request to ${baseUrl}register.php"
            )

            val request = Request.Builder()
                .url(baseUrl + "register.php")
                .post(json.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->

                Log.d(
                    "AuthApiClient",
                    "REGISTER: HTTP ${response.code}"
                )

                val text = response.body?.string()

                Log.d(
                    "AuthApiClient",
                    "REGISTER: response body = $text"
                )

                if (!response.isSuccessful || text == null) {
                    return@withContext null
                }

                JSONObject(text)
            }

        } catch (e: Exception) {

            Log.e(
                "AuthApiClient",
                "REGISTER: request failed",
                e
            )

            null
        }
    }

    suspend fun getProfile(
        userId: Long
    ): JSONObject? = withContext(Dispatchers.IO) {

        try {
            val json = JSONObject()
                .put("user_id", userId)

            Log.d(
                "AuthApiClient",
                "PROFILE: sending request to ${baseUrl}profile.php"
            )

            val request = Request.Builder()
                .url(baseUrl + "profile.php")
                .post(json.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { response ->

                Log.d(
                    "AuthApiClient",
                    "PROFILE: HTTP ${response.code}"
                )

                val text = response.body?.string()

                Log.d(
                    "AuthApiClient",
                    "PROFILE: response body = $text"
                )

                if (!response.isSuccessful || text == null) {
                    return@withContext null
                }

                JSONObject(text)
            }

        } catch (e: Exception) {

            Log.e(
                "AuthApiClient",
                "PROFILE: request failed",
                e
            )

            null
        }
    }
}
