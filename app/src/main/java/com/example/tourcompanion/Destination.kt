package com.example.tourcompanion

data class Destination(
    val id: Int,
    val name: String,
    val city: String,
    val category: String,
    val searchTags: String,
    val overview: String,
    val timings: String,
    val entryFee: String,
    val howToReach: String,
    val bestTimeToVisit: String,
    val thingsToDo: String,
    val safetyTips: String,
    val nearbyAttractions: String,
    val isActive: Int,
    val latitude: Double? = null,
    val longitude: Double? = null
)

