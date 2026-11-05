package com.example.tourcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Lightweight, suspend-free wrapper around Android framework's LocationManager.
 * Safely inspects permissions and retrieves last-known device coordinates without
 * continuous location updates or background tracking.
 */
class AppLocationManager(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager: LocationManager?
        get() = try {
            appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (_: Throwable) {
            null
        }

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
     * Retrieves the most recent valid last-known coordinates from available providers.
     * Returns null if permissions are absent, providers are disabled, coordinates are invalid,
     * or no cached location exists. Never invents coordinates.
     */
    fun getLastKnownCoordinates(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        val lm = locationManager ?: return null

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

            bestLocation?.let { loc ->
                val lat = loc.latitude
                val lon = loc.longitude
                if (isValidCoordinate(lat, lon)) {
                    Pair(lat, lon)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        fun isValidCoordinate(lat: Double, lon: Double): Boolean {
            if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return false
            if (lat < -90.0 || lat > 90.0) return false
            if (lon < -180.0 || lon > 180.0) return false
            if (lat == 0.0 && lon == 0.0) return false // Standard null island filter
            return true
        }
    }
}

