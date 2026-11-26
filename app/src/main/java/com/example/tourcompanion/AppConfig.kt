package com.example.tourcompanion

object AppConfig {

    const val BASE_URL = "http://172.23.176.210/tourcompanion/api/"

    /**
     * Endpoint for AI / Semmozhi travel answers.
     */
    const val AI_PROXY_URL = "${BASE_URL}ask.php"

    /**
     * Endpoint for GPS proximity-based nearby attractions.
     */
    const val DESTINATIONS_NEARBY_URL = "${BASE_URL}destinations-nearby.php"

    /**
     * Endpoint for GPS distance calculation to a specific destination.
     */
    const val DESTINATIONS_DISTANCE_URL = "${BASE_URL}destinations-distance.php"
}
