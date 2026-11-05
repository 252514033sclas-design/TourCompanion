package com.example.tourcompanion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain result wrapper for static public transport queries.
 */
sealed class TransportResult<out T> {
    data class Success<out T>(val data: T) : TransportResult<T>()
    object Empty : TransportResult<Nothing>()
    data class Notice(val message: String) : TransportResult<Nothing>()
    object NetworkUnavailable : TransportResult<Nothing>()
    data class ServerError(val code: Int, val message: String) : TransportResult<Nothing>()
}

/**
 * Repository orchestrating static Chennai public transport data.
 * Calls TransportApiClient and exposes clean, verified domain states to the application.
 */
class TransportRepository(
    context: Context? = null,
    private val api: TransportApiClient = TransportApiClient()
) {
    private val appContext = context?.applicationContext

    /**
     * Finds stops within [radiusKm] of [lat], [lon].
     */
    suspend fun getNearbyStops(
        lat: Double,
        lon: Double,
        radiusKm: Double = 2.0,
        agency: String? = null,
        mode: String? = null
    ): TransportResult<List<TransportStop>> = withContext(Dispatchers.IO) {
        when (val res = api.getNearbyStops(lat, lon, radiusKm, agency, mode)) {
            is TransportApiClient.Response.Success -> {
                if (res.data.isEmpty()) {
                    TransportResult.Empty
                } else {
                    TransportResult.Success(res.data)
                }
            }
            is TransportApiClient.Response.Error -> {
                TransportResult.ServerError(res.code, res.message)
            }
            is TransportApiClient.Response.Failure -> {
                TransportResult.NetworkUnavailable
            }
        }
    }

    /**
     * Searches static routes by number/name/color, agency, or mode.
     */
    suspend fun searchRoutes(
        query: String? = null,
        agency: String? = null,
        mode: String? = null
    ): TransportResult<List<TransportRoute>> = withContext(Dispatchers.IO) {
        when (val res = api.searchRoutes(query, agency, mode)) {
            is TransportApiClient.Response.Success -> {
                if (res.data.isEmpty()) {
                    TransportResult.Empty
                } else {
                    TransportResult.Success(res.data)
                }
            }
            is TransportApiClient.Response.Error -> {
                TransportResult.ServerError(res.code, res.message)
            }
            is TransportApiClient.Response.Failure -> {
                TransportResult.NetworkUnavailable
            }
        }
    }

    /**
     * Queries routes serving a specific stop.
     * Accurately reflects notice when route-stop mapping is unavailable in the static MVP dataset.
     */
    suspend fun getRoutesForStop(stopId: String): TransportResult<List<TransportRoute>> = withContext(Dispatchers.IO) {
        when (val res = api.getRoutesForStop(stopId)) {
            is TransportApiClient.Response.Success -> {
                if (!res.message.isNullOrBlank()) {
                    TransportResult.Notice(res.message)
                } else if (res.data.isEmpty()) {
                    TransportResult.Empty
                } else {
                    TransportResult.Success(res.data)
                }
            }
            is TransportApiClient.Response.Error -> {
                TransportResult.ServerError(res.code, res.message)
            }
            is TransportApiClient.Response.Failure -> {
                TransportResult.NetworkUnavailable
            }
        }
    }
}
