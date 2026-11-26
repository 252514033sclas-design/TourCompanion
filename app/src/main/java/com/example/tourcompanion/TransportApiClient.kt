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
 * Low-level HTTP client for Chennai static public transport backend APIs.
 * Connects to transport-nearby.php and transport-routes.php.
 */
class TransportApiClient(private val baseUrl: String = AppConfig.BASE_URL) {

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
     * Finds static public transport stops within [radiusKm] of [lat], [lon].
     */
    suspend fun getNearbyStops(
        lat: Double,
        lon: Double,
        radiusKm: Double = 2.0,
        agency: String? = null,
        mode: String? = null
    ): Response<List<TransportStop>> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl + "transport-nearby.php"
        val httpUrl = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("lat", lat.toString())
            addQueryParameter("lon", lon.toString())
            addQueryParameter("radius_km", radiusKm.toString())
            if (!agency.isNullOrBlank()) addQueryParameter("agency", agency.trim())
            if (!mode.isNullOrBlank()) addQueryParameter("mode", mode.trim())
        }?.build() ?: return@withContext Response.Error(400, "Invalid URL configuration")

        executeRequest(httpUrl) { json ->
            val dataArray = json.optJSONArray("data") ?: return@executeRequest emptyList()
            val stops = ArrayList<TransportStop>(dataArray.length())

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val agencyObj = item.optJSONObject("agency")
                val agency = if (agencyObj != null) {
                    TransportAgency(
                        agencyId = agencyObj.optString("agency_id"),
                        agencyName = agencyObj.optString("agency_name"),
                        agencyFullName = agencyObj.optString("agency_full_name")
                    )
                } else null

                val parentStation = if (item.isNull("parent_station") || item.optString("parent_station").isBlank()) {
                    null
                } else {
                    item.optString("parent_station")
                }

                stops.add(
                    TransportStop(
                        stopId = item.optString("stop_id"),
                        stopName = item.optString("stop_name"),
                        stopLat = item.optDouble("stop_lat"),
                        stopLon = item.optDouble("stop_lon"),
                        locationType = item.optInt("location_type", 0),
                        parentStation = parentStation,
                        distanceKm = item.optDouble("distance_km", 0.0),
                        agency = agency
                    )
                )
            }
            stops
        }
    }

    /**
     * Searches static routes by number/name/color, agency, mode, or origin/destination stops.
     */
    suspend fun searchRoutes(
        query: String? = null,
        agency: String? = null,
        mode: String? = null,
        origin: String? = null,
        destination: String? = null
    ): Response<List<TransportRoute>> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl + "transport-routes.php"
        val httpUrl = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            if (!query.isNullOrBlank()) addQueryParameter("q", query.trim())
            if (!agency.isNullOrBlank()) addQueryParameter("agency", agency.trim())
            if (!mode.isNullOrBlank()) addQueryParameter("mode", mode.trim())
            if (!origin.isNullOrBlank()) addQueryParameter("origin", origin.trim())
            if (!destination.isNullOrBlank()) addQueryParameter("destination", destination.trim())
        }?.build() ?: return@withContext Response.Error(400, "Invalid URL configuration")

        executeRequest(httpUrl) { json -> parseRoutes(json) }
    }

    /**
     * Queries routes serving a specific stop.
     * Note: In current static MVP without stop_times data, the backend returns a clear notice.
     */
    suspend fun getRoutesForStop(stopId: String): Response<List<TransportRoute>> = withContext(Dispatchers.IO) {
        val endpoint = baseUrl + "transport-routes.php"
        val httpUrl = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("stop_id", stopId.trim())
        }?.build() ?: return@withContext Response.Error(400, "Invalid URL configuration")

        executeRequest(httpUrl) { json -> parseRoutes(json) }
    }

    private fun parseRoutes(json: JSONObject): List<TransportRoute> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val routes = ArrayList<TransportRoute>(dataArray.length())

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)

            val agencyId = if (item.isNull("agency_id") || item.optString("agency_id").isBlank()) {
                null
            } else {
                item.optString("agency_id")
            }

            val agencyName = if (item.isNull("agency_name") || item.optString("agency_name").isBlank()) {
                null
            } else {
                item.optString("agency_name")
            }

            val routeDesc = if (item.isNull("route_desc") || item.optString("route_desc").isBlank()) {
                null
            } else {
                item.optString("route_desc")
            }

            val routeColor = if (item.isNull("route_color") || item.optString("route_color").isBlank()) {
                null
            } else {
                item.optString("route_color")
            }

            val routeTextColor = if (item.isNull("route_text_color") || item.optString("route_text_color").isBlank()) {
                null
            } else {
                item.optString("route_text_color")
            }

            routes.add(
                TransportRoute(
                    routeId = item.optString("route_id"),
                    agencyId = agencyId,
                    agencyName = agencyName,
                    routeShortName = item.optString("route_short_name"),
                    routeLongName = item.optString("route_long_name"),
                    routeType = item.optInt("route_type", 3),
                    routeDesc = routeDesc,
                    routeColor = routeColor,
                    routeTextColor = routeTextColor
                )
            )
        }
        return routes
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
                val count = json.optInt("count", 0)
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
        private const val TAG = "TransportApiClient"
    }
}

