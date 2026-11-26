package com.example.tourcompanion

/**
 * Represents a static public transit route (bus route, metro line, suburban rail service).
 * Note: agencyId is nullable because certain imported routes (e.g. CMRL metro routes in ChennaiGTFS)
 * have null/blank agency_id in the source GTFS dataset.
 */
data class TransportRoute(
    val routeId: String,
    val agencyId: String?,
    val agencyName: String?,
    val routeShortName: String,
    val routeLongName: String,
    val routeType: Int,
    val routeDesc: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null
)

