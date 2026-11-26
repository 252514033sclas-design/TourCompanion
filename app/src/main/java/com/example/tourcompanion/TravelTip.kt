package com.example.tourcompanion

data class TravelTip(
    val topic: String,
    val keywords: List<String>,
    val answer: String
)

data class TravelTipSync(
    val id: Long,
    val topic: String,
    val keywords: String,
    val answer: String
)
