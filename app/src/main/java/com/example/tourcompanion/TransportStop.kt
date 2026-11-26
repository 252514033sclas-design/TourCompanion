package com.example.tourcompanion

/**
 * Represents a static public transport stop or station (bus stop, metro station, suburban railway station).
 */
data class TransportStop(
    val stopId: String,
    val stopName: String,
    val stopLat: Double,
    val stopLon: Double,
    val locationType: Int = 0,
    val parentStation: String? = null,
    val distanceKm: Double = 0.0,
    val agency: TransportAgency? = null
)

