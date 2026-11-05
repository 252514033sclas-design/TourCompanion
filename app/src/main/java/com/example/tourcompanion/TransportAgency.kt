package com.example.tourcompanion

/**
 * Represents a public transport operating agency (e.g. MTC, CMRL, Southern Railway).
 */
data class TransportAgency(
    val agencyId: String,
    val agencyName: String,
    val agencyFullName: String
)

