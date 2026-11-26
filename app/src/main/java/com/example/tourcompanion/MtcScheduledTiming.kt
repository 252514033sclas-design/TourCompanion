package com.example.tourcompanion

/**
 * Structured model representing scheduled bus departure times from the official MTC website.
 *
 * NOTE: These are scheduled stage departure timings, NOT live GPS tracking or real-time vehicle arrivals.
 */
data class MtcScheduledTiming(
    val route: String,
    val origin: String,
    val destination: String,
    val times: List<String>,
    val source: String = "official_mtc_scheduled"
)
