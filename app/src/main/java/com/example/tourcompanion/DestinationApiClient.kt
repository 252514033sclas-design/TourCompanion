package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Low-level HTTP client for the Destination backend API.
 * Connects to destinations.php with GET requests.
 */
class DestinationApiClient(private val baseUrl: String = AppConfig.BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class Response<out T> {
        data class Success<out T>(val data: T, val count: Int, val message: String? = null) : Response<T>()
        data class Error(val code: Int, val message: String) : Response<Nothing>()
        data class Failure(val exception: Throwable) : Response<Nothing>()
    }

    /**
     * Fetches destinations from the backend with optional filtering.
     *
     * @param city Optional city name filter (e.g. "Chennai")
     * @param category Optional category filter (e.g. "Beach", "Heritage")
     * @param q Optional search keyword filter
     */
    suspend fun getDestinations(
        city: String? = null,
        category: String? = null,
        q: String? = null
    ): Response<List<Destination>> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl + "destinations.php"
        val httpUrl = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            if (!city.isNullOrBlank()) addQueryParameter("city", city.trim())
            if (!category.isNullOrBlank()) addQueryParameter("category", category.trim())
            if (!q.isNullOrBlank()) addQueryParameter("q", q.trim())
        }?.build() ?: return@withContext Response.Error(400, "Invalid URL configuration")

        executeRequest(httpUrl) { json -> parseDestinations(json) }
    }

    /**
     * Alias for [getDestinations] adhering to naming conventions in the project.
     */
    suspend fun fetchDestinations(
        city: String? = null,
        category: String? = null,
        q: String? = null
    ): Response<List<Destination>> = getDestinations(city = city, category = category, q = q)

    /**
     * Fetches verified destination aliases from the backend (destination-aliases.php).
     */
    suspend fun getDestinationAliases(): Response<List<DestinationAlias>> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl + "destination-aliases.php"
        val httpUrl = endpoint.toHttpUrlOrNull()?.newBuilder()?.build()
            ?: return@withContext Response.Error(400, "Invalid URL configuration")

        executeRequest(httpUrl) { json -> parseDestinationAliases(json) }
    }

    /**
     * Alias for [getDestinationAliases].
     */
    suspend fun fetchDestinationAliases(): Response<List<DestinationAlias>> = getDestinationAliases()

    /**
     * Parses the JSON response into a list of [DestinationAlias] domain models.
     */
    fun parseDestinationAliases(json: JSONObject): List<DestinationAlias> {
        val array = json.optJSONArray("aliases") ?: json.optJSONArray("data") ?: return emptyList()
        val list = ArrayList<DestinationAlias>(array.length())

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            val destinationId = if (item.has("destination_id")) {
                item.optInt("destination_id")
            } else {
                item.optInt("destinationId")
            }
            val canonicalName = if (item.has("destination_name")) {
                item.optString("destination_name")
            } else {
                item.optString("canonicalName")
            }
            val alias = item.optString("alias")
            val language = item.optString("language", "en")

            if (alias.isNotBlank() && destinationId > 0) {
                list.add(
                    DestinationAlias(
                        id = id,
                        destinationId = destinationId,
                        canonicalName = canonicalName,
                        alias = alias,
                        language = language
                    )
                )
            }
        }
        return list
    }

    /**
     * Parses the JSON response into a list of [Destination] domain models.
     */
    fun parseDestinations(json: JSONObject): List<Destination> {
        val array = json.optJSONArray("destinations") ?: json.optJSONArray("data") ?: return emptyList()
        val list = ArrayList<Destination>(array.length())

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val searchTags = if (item.has("search_tags")) {
                item.optString("search_tags")
            } else {
                item.optString("searchTags")
            }

            val entryFee = if (item.has("entry_fee")) {
                item.optString("entry_fee")
            } else {
                item.optString("entryFee")
            }

            val howToReach = if (item.has("how_to_reach")) {
                item.optString("how_to_reach")
            } else {
                item.optString("howToReach")
            }

            val bestTimeToVisit = if (item.has("best_time_to_visit")) {
                item.optString("best_time_to_visit")
            } else {
                item.optString("bestTimeToVisit")
            }

            val thingsToDo = if (item.has("things_to_do")) {
                item.optString("things_to_do")
            } else {
                item.optString("thingsToDo")
            }

            val safetyTips = if (item.has("safety_tips")) {
                item.optString("safety_tips")
            } else {
                item.optString("safetyTips")
            }

            val nearbyAttractions = if (item.has("nearby_attractions")) {
                item.optString("nearby_attractions")
            } else {
                item.optString("nearbyAttractions")
            }

            val isActive = if (item.has("is_active")) {
                item.optInt("is_active", 1)
            } else {
                item.optInt("isActive", 1)
            }

            val latitude = if (item.isNull("latitude") || !item.has("latitude")) {
                null
            } else {
                val d = item.optDouble("latitude")
                if (d.isNaN()) null else d
            }

            val longitude = if (item.isNull("longitude") || !item.has("longitude")) {
                null
            } else {
                val d = item.optDouble("longitude")
                if (d.isNaN()) null else d
            }

            list.add(
                Destination(
                    id = item.optInt("id"),
                    name = item.optString("name"),
                    city = item.optString("city"),
                    category = item.optString("category"),
                    searchTags = searchTags,
                    overview = item.optString("overview"),
                    timings = item.optString("timings"),
                    entryFee = entryFee,
                    howToReach = howToReach,
                    bestTimeToVisit = bestTimeToVisit,
                    thingsToDo = thingsToDo,
                    safetyTips = safetyTips,
                    nearbyAttractions = nearbyAttractions,
                    isActive = isActive,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
        return list
    }

    private fun <T> executeRequest(
        url: HttpUrl,
        parser: (JSONObject) -> T
    ): Response<T> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(bodyStr).optString("message", "HTTP $code error")
                    } catch (e: Exception) {
                        "HTTP $code error"
                    }
                    return Response.Error(code, errorMsg)
                }

                val json = try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    return Response.Error(code, "Malformed JSON response from server")
                }

                if (json.optString("status") != "success") {
                    val msg = json.optString("message", "Unknown error from server")
                    return Response.Error(code, msg)
                }

                val parsed = parser(json)
                val count = json.optInt("count", if (parsed is Collection<*>) parsed.size else 0)
                val message = if (json.has("message")) json.optString("message") else null
                Response.Success(parsed, count, message)
            }
        } catch (e: IOException) {
            logW(TAG, "Network failure calling $url: ${e.message}")
            Response.Failure(e)
        } catch (e: Exception) {
            logE(TAG, "Unexpected error calling $url: ${e.message}", e)
            Response.Failure(e)
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg")
        }
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            Log.e(tag, msg, tr)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg: ${tr?.message}")
        }
    }

    companion object {
        private const val TAG = "DestinationApiClient"
    }
}

