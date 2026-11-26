package com.example.tourcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Robust wrapper around Android framework's LocationManager.
 * Safely inspects permissions and retrieves last-known device coordinates as well as
 * single fresh location fixes via [LocationManagerCompat.getCurrentLocation] when needed,
 * without continuous polling loops or background tracking.
 */
class AppLocationManager(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager: LocationManager?
        get() = try {
            appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (_: Throwable) {
            null
        }

    @Volatile
    private var inMemoryCoordinates: Pair<Double, Double>? = null

    private val inFlightLock = Any()
    @Volatile
    private var inFlightRequest: Deferred<Pair<Double, Double>?>? = null

    fun isLocationManagerAvailable(): Boolean = locationManager != null

    /**
     * Checks if either fine or coarse location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return try {
            val fine = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if at least one location provider is enabled.
     */
    fun isLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gps || network
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Asynchronously acquires a fresh single location fix:
     * 1. If a sufficiently recent cached location (< 2 min old) already exists, returns it immediately.
     * 2. Otherwise requests a fresh single location using [LocationManagerCompat.getCurrentLocation].
     * 3. Falls back to a usable cached location (< 30 min old) if fresh request times out.
     * 4. Deduplicates simultaneous requests via an in-flight guard.
     */
    suspend fun getFreshCoordinates(timeoutMs: Long = 8000L): Pair<Double, Double>? {
        val existing = synchronized(inFlightLock) { inFlightRequest }
        if (existing != null && existing.isActive) {
            logD(TAG, "Reusing in-flight location request")
            return try {
                existing.await()
            } catch (_: Throwable) {
                null
            }
        }

        return coroutineScope {
            val deferred = async {
                performLocationAcquisition(timeoutMs)
            }
            synchronized(inFlightLock) {
                inFlightRequest = deferred
            }
            try {
                deferred.await()
            } finally {
                synchronized(inFlightLock) {
                    if (inFlightRequest === deferred) {
                        inFlightRequest = null
                    }
                }
            }
        }
    }

    private suspend fun performLocationAcquisition(timeoutMs: Long): Pair<Double, Double>? {
        if (!hasLocationPermission()) {
            logD(TAG, "Location permission denied")
            return null
        }
        logD(TAG, "Location permission granted")

        if (!isLocationEnabled()) {
            logD(TAG, "Location services disabled")
            return null
        }

        // 1. Check recent cached location (< 2 min old)
        val recentCached = getCachedLocationWithin(RECENT_CACHE_THRESHOLD_MS)
        if (recentCached != null) {
            val ageMs = getLocationAgeMs(recentCached)
            logD(TAG, "Location fix received (recent cache) lat=${recentCached.latitude} lon=${recentCached.longitude} accuracy=${recentCached.accuracy} ageMs=$ageMs")
            val coords = Pair(recentCached.latitude, recentCached.longitude)
            inMemoryCoordinates = coords
            return coords
        }

        // 2. Request fresh location from best available provider
        logD(TAG, "Location request started")
        val lm = locationManager
        if (lm == null) {
            logD(TAG, "Location manager unavailable")
            return inMemoryCoordinates
        }

        val freshLocation = withTimeoutOrNull(timeoutMs) {
            fetchSingleLocation(lm)
        }

        if (freshLocation != null && isValidCoordinate(freshLocation.latitude, freshLocation.longitude)) {
            val ageMs = getLocationAgeMs(freshLocation)
            logD(TAG, "Location fix received lat=${freshLocation.latitude} lon=${freshLocation.longitude} accuracy=${freshLocation.accuracy} ageMs=$ageMs")
            val coords = Pair(freshLocation.latitude, freshLocation.longitude)
            inMemoryCoordinates = coords
            return coords
        }

        logD(TAG, "Location request timed out")

        // 3. Fallback to older cached fix if within MAX_FALLBACK_CACHE_AGE_MS (30 mins)
        val fallbackCached = getCachedLocationWithin(MAX_FALLBACK_CACHE_AGE_MS)
        if (fallbackCached != null) {
            val ageMs = getLocationAgeMs(fallbackCached)
            logD(TAG, "Location fallback to cached fix lat=${fallbackCached.latitude} lon=${fallbackCached.longitude} accuracy=${fallbackCached.accuracy} ageMs=$ageMs")
            val coords = Pair(fallbackCached.latitude, fallbackCached.longitude)
            inMemoryCoordinates = coords
            return coords
        }

        logD(TAG, "Location fix unavailable")
        return null
    }

    private suspend fun fetchSingleLocation(lm: LocationManager): Location? {
        val provider = getBestProvider(lm) ?: return null
        logD(TAG, "Requesting fresh location fix from provider: $provider")
        return requestLocationCompat(lm, provider)
    }

    private fun getBestProvider(lm: LocationManager): String? {
        // 1. GPS satellite provider (direct satellite fix, hardware GNSS, emulator emu geo fix)
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER
            }
        } catch (_: Throwable) {}
        // 2. Fused provider (Android 12+ / API 31+) combines GNSS + Network
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                    lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
                ) {
                    return LocationManager.FUSED_PROVIDER
                }
            } catch (_: Throwable) {}
        }
        // 3. Network cellular/Wi-Fi provider
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER
            }
        } catch (_: Throwable) {}
        // 4. Passive provider fallback
        try {
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                return LocationManager.PASSIVE_PROVIDER
            }
        } catch (_: Throwable) {}

        return null
    }

    private suspend fun requestLocationCompat(
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        val cancellationSignal = CancellationSignal()
        cont.invokeOnCancellation {
            try {
                cancellationSignal.cancel()
            } catch (_: Throwable) {}
        }

        try {
            val executor = ContextCompat.getMainExecutor(appContext)
            LocationManagerCompat.getCurrentLocation(
                lm,
                provider,
                cancellationSignal,
                executor,
                Consumer { location ->
                    if (cont.isActive) {
                        cont.resume(location)
                    }
                }
            )
        } catch (_: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun getLocationAgeMs(loc: Location): Long {
        return try {
            val elapsed = LocationCompat.getElapsedRealtimeMillis(loc)
            val nowElapsed = SystemClock.elapsedRealtime()
            val diff = nowElapsed - elapsed
            if (diff >= 0L) diff else kotlin.math.max(0L, System.currentTimeMillis() - loc.time)
        } catch (_: Throwable) {
            kotlin.math.max(0L, System.currentTimeMillis() - loc.time)
        }
    }

    private fun getCachedLocationWithin(maxAgeMs: Long): Location? {
        val lm = locationManager ?: return null
        if (!hasLocationPermission()) return null

        val providers = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(0, LocationManager.FUSED_PROVIDER)
        }

        var bestLocation: Location? = null

        for (provider in providers) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                val loc = lm.getLastKnownLocation(provider) ?: continue
                val age = getLocationAgeMs(loc)
                if (age in 0..maxAgeMs) {
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        if (isValidCoordinate(loc.latitude, loc.longitude)) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return bestLocation
    }

    /**
     * Updates and retrieves the most recent valid coordinates from available providers.
     * Caches the result in memory so foreground features can reuse it without repeatedly
     * querying providers.
     *
     * Returns null if permissions are absent, providers are disabled, coordinates are invalid,
     * or no location exists. Never invents coordinates.
     */
    fun getLastKnownCoordinates(): Pair<Double, Double>? {
        if (!hasLocationPermission()) {
            inMemoryCoordinates = null
            return null
        }

        val lm = locationManager ?: return inMemoryCoordinates

        return try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            var bestLocation: Location? = null

            for (provider in providers) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue
                    val loc = lm.getLastKnownLocation(provider) ?: continue

                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                } catch (_: SecurityException) {
                    // Ignored: permission check handled above
                } catch (_: Throwable) {
                    // Ignored: provider not supported or unavailable
                }
            }

            val coords = bestLocation?.let { loc ->
                val lat = loc.latitude
                val lon = loc.longitude
                if (isValidCoordinate(lat, lon)) {
                    Pair(lat, lon)
                } else {
                    null
                }
            }

            if (coords != null) {
                inMemoryCoordinates = coords
            }

            coords ?: inMemoryCoordinates
        } catch (_: Throwable) {
            inMemoryCoordinates
        }
    }

    /**
     * Returns the latest in-memory location if available, or attempts a fresh lookup.
     */
    fun getLatestCoordinates(): Pair<Double, Double>? {
        return getLastKnownCoordinates() ?: inMemoryCoordinates
    }

    /**
     * Sets in-memory coordinates explicitly (used for testing or verified updates).
     */
    fun setInMemoryCoordinates(lat: Double, lon: Double) {
        if (isValidCoordinate(lat, lon)) {
            inMemoryCoordinates = Pair(lat, lon)
        }
    }

    /**
     * Clears in-memory coordinates when stale or permissions revoked.
     */
    fun clearInMemoryCoordinates() {
        inMemoryCoordinates = null
    }

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    companion object {
        private const val TAG = "AppLocationManager"

        /** Locations newer than 2 minutes are considered recent enough to use immediately. */
        const val RECENT_CACHE_THRESHOLD_MS = 2 * 60 * 1000L

        /** Locations older than 30 minutes are considered too stale to serve as fallback. */
        const val MAX_FALLBACK_CACHE_AGE_MS = 30 * 60 * 1000L

        fun isValidCoordinate(lat: Double, lon: Double): Boolean {
            if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return false
            if (lat < -90.0 || lat > 90.0) return false
            if (lon < -180.0 || lon > 180.0) return false
            if (lat == 0.0 && lon == 0.0) return false // Standard null island filter
            return true
        }
    }
}
