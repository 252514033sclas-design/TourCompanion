package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UsefulPhrasesApiClient(private val baseUrl: String = AppConfig.BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchUsefulPhrases(): List<UsefulPhraseSync>? = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl + "useful-phrases.php"
            Log.d("UsefulPhrasesApi", "FETCH_PHRASES: sending request to $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("UsefulPhrasesApi", "FETCH_PHRASES: HTTP ${response.code}")

                if (!response.isSuccessful) {
                    return@withContext null
                }

                val text = response.body?.string() ?: return@withContext null
                val json = JSONObject(text)

                if (json.optString("status") != "ok") {
                    return@withContext null
                }

                val array = json.optJSONArray("data") ?: return@withContext null
                val phrases = ArrayList<UsefulPhraseSync>()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    phrases.add(
                        UsefulPhraseSync(
                            id = item.optLong("id"),
                            language = item.optString("language"),
                            category = item.optString("category"),
                            phrase = item.optString("phrase"),
                            meaning = item.optString("meaning")
                        )
                    )
                }

                Log.d("UsefulPhrasesApi", "FETCH_PHRASES: successfully parsed ${phrases.size} phrases")
                phrases
            }
        } catch (e: Exception) {
            Log.w("UsefulPhrasesApi", "FETCH_PHRASES: request failed", e)
            null
        }
    }
}

