package com.example.tourcompanion

object AppConfig {

    const val BASE_URL = "http://172.23.176.210/tourcompanion/api/"

    /**
     * Primary endpoint for AI / Semmozhi travel query and knowledge answers.
     * Migrated to query.php (deterministic routing + grounded Gemma synthesis).
     */
    const val QUERY_URL = "${BASE_URL}query.php"

    /**
     * Endpoint for AI / Semmozhi travel answers (points to query.php).
     */
    const val AI_PROXY_URL = QUERY_URL

    /**
     * Endpoint for GPS proximity-based nearby attractions.
     */
    const val DESTINATIONS_NEARBY_URL = "${BASE_URL}destinations-nearby.php"

    /**
     * Endpoint for GPS distance calculation to a specific destination.
     */
    const val DESTINATIONS_DISTANCE_URL = "${BASE_URL}destinations-distance.php"
}
