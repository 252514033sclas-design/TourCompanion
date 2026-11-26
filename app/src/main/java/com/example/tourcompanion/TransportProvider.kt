package com.example.tourcompanion

/**
 * Domain result representing the outcome of an MTC dynamic timing lookup.
 */
sealed class MtcTimingResult {
    data class Success(val timing: MtcScheduledTiming) : MtcTimingResult()
    data class MultipleOrigins(
        val route: String,
        val origins: List<String>,
        val message: String
    ) : MtcTimingResult()
    data class NoData(val message: String) : MtcTimingResult()
    data class Error(val message: String, val cause: Throwable? = null) : MtcTimingResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Abstraction for dynamic public transport route timing providers.
 */
interface TransportTimingProvider {
    suspend fun getRouteTiming(
        route: String,
        origin: String? = null,
        destination: String? = null
    ): MtcTimingResult
}
