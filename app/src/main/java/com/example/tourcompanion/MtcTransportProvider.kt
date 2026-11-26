package com.example.tourcompanion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Production MTC Dynamic Transport Provider.
 * Connects at request time to the official MTC website (mtcbus.tn.gov.in) endpoints
 * to retrieve scheduled bus departure times.
 *
 * Implements Priority 1 in Tour Companion's transport resolution hierarchy.
 * Never fabricates real-time GPS locations, arrival ETAs, or fake timings.
 */
class MtcTransportProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val mtcBaseUrl: String = "https://mtcbus.tn.gov.in/Home/",
    private val backendProxyUrl: String = "${AppConfig.BASE_URL}mtc-timing.php",
    private val customHttpFetcher: (suspend (url: String) -> Pair<Int, String?>)? = null
) : TransportTimingProvider {

    companion object {
        private const val TAG = "MtcTransportProvider"
        // Active CSRF token fallback (PoC proved token is accepted dynamically or across calls)
        private const val DEFAULT_CSRF_TOKEN = "f377f0d9102f3ade66916c76e6769191"
    }

    private var cachedCsrfToken: String = DEFAULT_CSRF_TOKEN

    /**
     * Executes dynamic route timing lookup across MTC endpoints.
     */
    override suspend fun getRouteTiming(
        route: String,
        origin: String?,
        destination: String?
    ): MtcTimingResult = withContext(Dispatchers.IO) {
        val cleanRoute = route.trim().uppercase(Locale.ROOT)
        if (cleanRoute.isBlank()) {
            return@withContext MtcTimingResult.NoData("Route name or number cannot be empty.")
        }

        // Reject non-bus/metro routes from MTC dynamic bus lookup
        if (cleanRoute.contains("BLUE") || cleanRoute.contains("GREEN") || cleanRoute.contains("METRO")) {
            return@withContext MtcTimingResult.NoData("MTC provider only supports MTC bus routes.")
        }

        // Strategy 1: Attempt local backend proxy first if configured and reachable
        tryBackendProxy(cleanRoute, origin, destination)?.let { proxyResult ->
            return@withContext proxyResult
        }

        // Strategy 2: Direct official MTC website dynamic endpoints
        try {
            // Step 1: Discover available origins for the route
            val origins = fetchOrigins(cleanRoute)
            if (origins.isEmpty()) {
                return@withContext MtcTimingResult.NoData(
                    "No origins found on MTC website for route $cleanRoute."
                )
            }

            // Correction 1: Never auto-select the first origin when multiple exist and user did not specify one
            val selectedOrigin: String
            if (!origin.isNullOrBlank()) {
                val userOrig = origin.trim()
                val matched = origins.firstOrNull {
                    it.contains(userOrig, ignoreCase = true) || userOrig.contains(it, ignoreCase = true)
                }
                if (matched != null) {
                    selectedOrigin = matched
                } else {
                    return@withContext MtcTimingResult.NoData(
                        "No matching starting point '$origin' found for route $cleanRoute on MTC website."
                    )
                }
            } else {
                if (origins.size == 1) {
                    selectedOrigin = origins.first()
                } else {
                    val exampleOrigins = origins.take(2).map { formatOriginExample(it) }.joinToString(" or ")
                    val message = "Route $cleanRoute has multiple starting points. Please tell me your starting point, such as $exampleOrigins."
                    return@withContext MtcTimingResult.MultipleOrigins(
                        route = cleanRoute,
                        origins = origins,
                        message = message
                    )
                }
            }

            // Step 2: Discover available destinations for route + selected origin
            val destinations = fetchDestinations(cleanRoute, selectedOrigin)
            if (destinations.isEmpty()) {
                return@withContext MtcTimingResult.NoData(
                    "No destinations found on MTC website for route $cleanRoute from $selectedOrigin."
                )
            }

            // Match destination: preference to user requested destination, fallback to first available
            val selectedDest = if (!destination.isNullOrBlank()) {
                val userDest = destination.trim()
                val matched = destinations.firstOrNull {
                    it.contains(userDest, ignoreCase = true) || userDest.contains(it, ignoreCase = true)
                }
                if (matched != null) {
                    matched
                } else {
                    return@withContext MtcTimingResult.NoData(
                        "No matching destination '$destination' found for route $cleanRoute from $selectedOrigin on MTC website."
                    )
                }
            } else {
                destinations.first()
            }

            // Step 3: Fetch scheduled departure timings
            val timings = fetchTimings(cleanRoute, selectedOrigin, selectedDest)
            if (timings.isEmpty()) {
                return@withContext MtcTimingResult.NoData(
                    "No scheduled departure times found on MTC website for route $cleanRoute from $selectedOrigin to $selectedDest."
                )
            }

            MtcTimingResult.Success(
                MtcScheduledTiming(
                    route = cleanRoute,
                    origin = selectedOrigin,
                    destination = selectedDest,
                    times = timings,
                    source = "official_mtc_scheduled"
                )
            )
        } catch (e: Exception) {
            logW(TAG, "Error querying MTC endpoints for route $cleanRoute: ${e.message}")
            MtcTimingResult.Error("Failed to retrieve MTC route timings: ${e.message}", e)
        }
    }

    /**
     * Fetches route origin terminals.
     */
    suspend fun fetchOrigins(route: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = mtcBaseUrl + "getoriginbyroute"
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("csrf_test_name", cachedCsrfToken)
            addQueryParameter("selroute", route.trim())
            addQueryParameter("selfrom", "")
            addQueryParameter("selto", "")
        }?.build()?.toString() ?: return@withContext emptyList()

        val (code, html) = executeHttp(url)
        if (code == 200 && !html.isNullOrBlank()) {
            MtcHtmlParser.extractCsrfToken(html)?.let { cachedCsrfToken = it }
            MtcHtmlParser.parseOrigins(html)
        } else {
            emptyList()
        }
    }

    /**
     * Fetches available destinations for a route and origin.
     */
    suspend fun fetchDestinations(route: String, origin: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = mtcBaseUrl + "getdestinationrouteorigin"
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("csrf_test_name", cachedCsrfToken)
            addQueryParameter("selroute", route.trim())
            addQueryParameter("selfrom", origin.trim())
            addQueryParameter("selto", "")
        }?.build()?.toString() ?: return@withContext emptyList()

        val (code, html) = executeHttp(url)
        if (code == 200 && !html.isNullOrBlank()) {
            MtcHtmlParser.extractCsrfToken(html)?.let { cachedCsrfToken = it }
            MtcHtmlParser.parseDestinations(html)
        } else {
            emptyList()
        }
    }

    /**
     * Fetches scheduled departure timings for a route, origin, and destination.
     */
    suspend fun fetchTimings(route: String, origin: String, destination: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = mtcBaseUrl + "ajaxbustimingsearch"
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("csrf_test_name", cachedCsrfToken)
            addQueryParameter("selroute", route.trim())
            addQueryParameter("selfrom", origin.trim())
            addQueryParameter("selto", destination.trim())
        }?.build()?.toString() ?: return@withContext emptyList()

        val (code, html) = executeHttp(url)
        if (code == 200 && !html.isNullOrBlank()) {
            MtcHtmlParser.extractCsrfToken(html)?.let { cachedCsrfToken = it }
            MtcHtmlParser.parseTimings(html)
        } else {
            emptyList()
        }
    }

    private fun formatOriginExample(raw: String): String {
        val trimmed = raw.trim()
        val cleaned = trimmed.replace(Regex("(?i)\\bB\\.?S\\.?$"), "").trim()
        return cleaned.split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.equals("M.G.R.CENTRAL", ignoreCase = true)) {
                "M.G.R. Central"
            } else {
                word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
            }
        }
    }

    private suspend fun tryBackendProxy(
        route: String,
        origin: String?,
        destination: String?
    ): MtcTimingResult? = withContext(Dispatchers.IO) {
        if (backendProxyUrl.isBlank()) return@withContext null

        val proxyUrl = backendProxyUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("route", route)
            if (!origin.isNullOrBlank()) addQueryParameter("from", origin.trim())
            if (!destination.isNullOrBlank()) addQueryParameter("to", destination.trim())
        }?.build()?.toString() ?: return@withContext null

        try {
            val (code, body) = executeHttp(proxyUrl)
            if (code == 200 && !body.isNullOrBlank()) {
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status == "success") {
                    val timesArray = json.optJSONArray("timings")
                    val timesList = ArrayList<String>()
                    if (timesArray != null) {
                        for (i in 0 until timesArray.length()) {
                            timesList.add(timesArray.getString(i))
                        }
                    }
                    if (timesList.isNotEmpty()) {
                        return@withContext MtcTimingResult.Success(
                            MtcScheduledTiming(
                                route = json.optString("route", route),
                                origin = json.optString("origin", origin ?: ""),
                                destination = json.optString("destination", destination ?: ""),
                                times = timesList,
                                source = json.optString("source", "official_mtc_scheduled")
                            )
                        )
                    }
                } else if (status == "multiple_origins") {
                    val originsArray = json.optJSONArray("origins")
                    val originsList = ArrayList<String>()
                    if (originsArray != null) {
                        for (i in 0 until originsArray.length()) {
                            originsList.add(originsArray.getString(i))
                        }
                    }
                    val msg = json.optString("message", "Route $route has multiple starting points. Please tell me your starting point.")
                    return@withContext MtcTimingResult.MultipleOrigins(
                        route = json.optString("route", route),
                        origins = originsList,
                        message = msg
                    )
                }
            }
        } catch (_: Exception) {
            // Silently fall through to direct MTC website endpoints
        }
        null
    }

    private suspend fun executeHttp(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        if (customHttpFetcher != null) {
            return@withContext customHttpFetcher.invoke(url)
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TourCompanion/1.0")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://mtcbus.tn.gov.in/")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string()
                Pair(code, bodyStr)
            }
        } catch (e: IOException) {
            logW(TAG, "Network error calling $url: ${e.message}")
            Pair(0, null)
        } catch (e: Exception) {
            logE(TAG, "Unexpected error calling $url: ${e.message}", e)
            Pair(500, null)
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
}
