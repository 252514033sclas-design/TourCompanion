package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit and integration tests for [DestinationApiClient].
 * Tests JSON parsing, coordinate handling, UTF-8 preservation, error handling, and query parameters.
 */
class DestinationApiClientTest {

    private val testBaseUrl = "http://localhost/tourcompanion/api/"
    private val apiClient = DestinationApiClient(testBaseUrl)

    @Test
    fun testParseDestinationsValidJsonWithCoordinates() {
        val jsonStr = """
            {
                "status": "success",
                "count": 1,
                "destinations": [
                    {
                        "id": 1,
                        "name": "Marina Beach",
                        "city": "Chennai",
                        "category": "Beach & Promenade",
                        "latitude": 13.054180,
                        "longitude": 80.283680,
                        "search_tags": "marina, beach, lighthouse, promenade, sunrise",
                        "overview": "Marina Beach is an iconic natural urban beach in Chennai.",
                        "timings": "Open 24 hours daily",
                        "entry_fee": "Free public entry (Lighthouse ₹20)",
                        "how_to_reach": "Accessible by MTC bus or suburban train",
                        "best_time_to_visit": "November to February",
                        "things_to_do": "Walk along promenade, view sunrise",
                        "safety_tips": "Avoid swimming due to strong undertow",
                        "nearby_attractions": "San Thome Basilica, Fort St. George & St. Mary’s Church",
                        "is_active": 1
                    }
                ]
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val destinations = apiClient.parseDestinations(json)

        assertEquals(1, destinations.size)
        val d = destinations[0]
        assertEquals(1, d.id)
        assertEquals("Marina Beach", d.name)
        assertEquals("Chennai", d.city)
        assertEquals("Beach & Promenade", d.category)
        assertNotNull(d.latitude)
        assertNotNull(d.longitude)
        assertEquals(13.054180, d.latitude!!, 0.000001)
        assertEquals(80.283680, d.longitude!!, 0.000001)
        assertEquals("marina, beach, lighthouse, promenade, sunrise", d.searchTags)
        assertEquals("Marina Beach is an iconic natural urban beach in Chennai.", d.overview)
        assertEquals("Open 24 hours daily", d.timings)
        assertEquals("Free public entry (Lighthouse ₹20)", d.entryFee)
        assertTrue("UTF-8 ₹ symbol must be preserved", d.entryFee.contains("₹"))
        assertTrue("UTF-8 curly apostrophe ’ must be preserved", d.nearbyAttractions.contains("’"))
        assertEquals(1, d.isActive)
    }

    @Test
    fun testParseDestinationsNullCoordinates() {
        val jsonStr = """
            {
                "status": "success",
                "count": 1,
                "destinations": [
                    {
                        "id": 99,
                        "name": "Future Destination",
                        "city": "Chennai",
                        "category": "Heritage",
                        "latitude": null,
                        "longitude": null,
                        "search_tags": "future",
                        "overview": "Overview text",
                        "timings": "9 AM - 5 PM",
                        "entry_fee": "Free",
                        "how_to_reach": "Bus",
                        "best_time_to_visit": "Morning",
                        "things_to_do": "Visit",
                        "safety_tips": "Stay safe",
                        "nearby_attractions": "None",
                        "is_active": 1
                    }
                ]
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val destinations = apiClient.parseDestinations(json)

        assertEquals(1, destinations.size)
        val d = destinations[0]
        assertEquals(99, d.id)
        assertNull("latitude must be null when null in JSON", d.latitude)
        assertNull("longitude must be null when null in JSON", d.longitude)
    }

    @Test
    fun testParseDestinationsEmptyList() {
        val jsonStr = """
            {
                "status": "success",
                "count": 0,
                "destinations": []
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val destinations = apiClient.parseDestinations(json)

        assertNotNull(destinations)
        assertTrue(destinations.isEmpty())
    }

    @Test
    fun testParseDestinationsMissingOrMalformedArray() {
        val jsonStr = """
            {
                "status": "success",
                "count": 0
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val destinations = apiClient.parseDestinations(json)

        assertNotNull(destinations)
        assertTrue(destinations.isEmpty())
    }

    @Test
    fun testLiveBackendFetchChennaiDestinations() = runBlocking {
        val response = apiClient.getDestinations(city = "Chennai")
        if (response is DestinationApiClient.Response.Success) {
            assertTrue("Expected at least 20 Chennai destinations, found ${response.count}", response.count >= 20)
            assertTrue("Expected at least 20 data rows, found ${response.data.size}", response.data.size >= 20)
            assertTrue(response.data.any { it.name == "Marina Beach" })

            val marina = response.data.first { it.name == "Marina Beach" }
            assertNotNull("Marina Beach latitude must not be null", marina.latitude)
            assertNotNull("Marina Beach longitude must not be null", marina.longitude)
            assertEquals(13.054180, marina.latitude!!, 0.0001)
            assertEquals(80.283680, marina.longitude!!, 0.0001)

            val kapaleeshwarar = response.data.first { it.name.contains("Kapaleeshwarar") }
            assertNotNull(kapaleeshwarar.latitude)
            assertNotNull(kapaleeshwarar.longitude)
            assertEquals(13.033700, kapaleeshwarar.latitude!!, 0.0001)
            assertEquals(80.269900, kapaleeshwarar.longitude!!, 0.0001)
        } else {
            assertTrue("Should be Failure when server is not reachable", response is DestinationApiClient.Response.Failure)
        }
    }

    @Test
    fun testLiveBackendSearchQuery() = runBlocking {
        val response = apiClient.getDestinations(q = "marina")
        if (response is DestinationApiClient.Response.Success) {
            assertTrue(response.data.isNotEmpty())
            val first = response.data[0]
            assertEquals("Marina Beach", first.name)
            assertNotNull(first.latitude)
            assertNotNull(first.longitude)
            assertEquals(13.054180, first.latitude!!, 0.0001)
            assertEquals(80.283680, first.longitude!!, 0.0001)
        }
    }

    @Test
    fun testLiveBackendNonExistentQuery() = runBlocking {
        val response = apiClient.getDestinations(q = "nonexistent_destination_12345")
        if (response is DestinationApiClient.Response.Success) {
            assertEquals(0, response.count)
            assertTrue(response.data.isEmpty())
        }
    }
}
