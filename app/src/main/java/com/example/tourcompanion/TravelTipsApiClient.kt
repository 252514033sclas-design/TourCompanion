package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TravelTipsApiClient(private val baseUrl: String = AppConfig.BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchTravelTips(): List<TravelTipSync>? = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl + "travel-tips.php"
            Log.d("TravelTipsApiClient", "FETCH_TIPS: sending request to $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("TravelTipsApiClient", "FETCH_TIPS: HTTP ${response.code}")

                if (!response.isSuccessful) {
                    return@withContext null
                }

                val text = response.body?.string() ?: return@withContext null
                val json = JSONObject(text)

                if (json.optString("status") != "ok") {
                    return@withContext null
                }

                val array = json.optJSONArray("data") ?: return@withContext null
                val tips = ArrayList<TravelTipSync>()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    tips.add(
                        TravelTipSync(
                            id = item.optLong("id"),
                            topic = item.optString("topic"),
                            keywords = item.optString("keywords"),
                            answer = item.optString("answer")
                        )
                    )
                }

                Log.d("TravelTipsApiClient", "FETCH_TIPS: successfully parsed ${tips.size} tips")
                tips
            }
        } catch (e: Exception) {
            Log.w("TravelTipsApiClient", "FETCH_TIPS: request failed", e)
            null
        }
    }
}

