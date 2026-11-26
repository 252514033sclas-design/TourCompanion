package com.example.tourcompanion

/**
 * Represents a single leg of a public transit transfer journey.
 */
data class TransportLeg(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val directionId: Int = 0,
    val boardStopId: String,
    val boardStopName: String,
    val boardStopSequence: Int,
    val alightStopId: String,
    val alightStopName: String,
    val alightStopSequence: Int,
    val hops: Int
)

/**
 * Represents a verified 2-leg transfer transit journey.
 */
data class TransferJourney(
    val leg1: TransportLeg,
    val transferStopId: String,
    val transferStopName: String,
    val leg2: TransportLeg,
    val totalHops: Int,
    val isTransfer: Boolean = true
)

/**
 * Represents a static public transit route (bus route, metro line, suburban rail service),
 * or a 2-leg transfer option when [transferJourney] is populated.
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
    val routeTextColor: String? = null,
    val transferJourney: TransferJourney? = null,
    val boardStopId: String? = null,
    val boardStopName: String? = null,
    val boardStopSequence: Int? = null,
    val alightStopId: String? = null,
    val alightStopName: String? = null,
    val alightStopSequence: Int? = null,
    val headsign: String? = null,
    val directionId: Int? = null
)


