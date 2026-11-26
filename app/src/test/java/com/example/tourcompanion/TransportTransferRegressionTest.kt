package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test suite verifying Android-only 2-leg bus transfer response parsing
 * and QueryProcessor output formatting against live and mocked backend responses.
 */
class TransportTransferRegressionTest {

    private val testBaseUrl = "http://localhost/tourcompanion/api/"
    private val apiClient = TransportApiClient(testBaseUrl)
    private val repo = TransportRepository(api = apiClient)

    // =========================================================================
    // Test 1 — Direct bus with verified boarding & alighting stops
    // =========================================================================
    @Test
    fun test01_DirectBus_WithVerifiedBoardingAlightingStops() = runBlocking {
        val mockRoute = TransportRoute(
            routeId = "MTC-19951",
            agencyId = "69",
            agencyName = "MTC",
            routeShortName = "25K",
            routeLongName = "Poonamallee Bus Terminus TO Marina Beach",
            routeType = 3,
            boardStopName = "Poonamallee Government Hospital",
            alightStopName = "Kannagi Statue",
            headsign = "Marina Beach"
        )
        val mockRepo = object : TransportRepository(api = apiClient) {
            override suspend fun searchRoutes(
                query: String?,
                agency: String?,
                mode: String?,
                origin: String?,
                destination: String?
            ): TransportResult<List<TransportRoute>> {
                return TransportResult.Success(listOf(mockRoute))
            }
        }
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Poonamallee",
            destination = "Marina Beach",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, mockRepo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertEquals(
            "You can take Bus 25K toward Marina Beach.\n\nBoard at Poonamallee Government Hospital\nand get down at Kannagi Statue.",
            answer
        )
        assertFalse("Must NOT contain invented distance", answer.contains(" km"))
        assertFalse("Must NOT contain invented travel time", answer.contains("minutes"))
    }

    // =========================================================================
    // Test 2 — Direct bus without boarding data: Poonamallee -> Valluvar Kottam
    // =========================================================================
    @Test
    fun test02_DirectBus_PoonamalleeToValluvarKottam_PreservesExistingListing() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Poonamallee",
            destination = "Valluvar Kottam",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertFalse("Must NOT contain transfer wording", answer.contains("No direct bus was found", ignoreCase = true))
        assertFalse("Must NOT contain transfer steps", answer.contains("change to Bus", ignoreCase = true))
        assertTrue("Must show direct routes listing", answer.contains("26 R1") || answer.contains("37 R"))
        assertTrue("Must have verified direct routes header", answer.contains("verified direct routes from Poonamallee to Valluvar Kottam"))
    }

    // =========================================================================
    // Test 3 — Bus transfer: Triplicane -> Valluvar Kottam
    // =========================================================================
    @Test
    fun test03_BusTransfer_TriplicaneToValluvarKottam() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Triplicane",
            destination = "Valluvar Kottam",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertTrue("Must include transfer header", answer.startsWith("No direct bus was found. You can take a 2-bus route:"))
        assertTrue("Step 1 board 40H at Triplicane", answer.contains("1. Board Bus 40H at Triplicane and get down at D P I."))
        assertTrue("Step 2 change to 37E at D P I", answer.contains("2. At D P I, change to Bus 37E and get down at Valluvar Kottam."))
        assertFalse("Must NOT contain invented travel time or minutes", answer.contains("minutes", ignoreCase = true))
        assertFalse("Must NOT contain invented distance", answer.contains(" km", ignoreCase = true))
    }

    // =========================================================================
    // Test 4 — Bus transfer: Poonamallee -> Government Museum, Egmore
    // =========================================================================
    @Test
    fun test04_BusTransfer_PoonamalleeToGovernmentMuseumEgmore() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Poonamallee",
            destination = "Government Museum, Egmore",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertTrue("Must show 2-bus route transfer", answer.startsWith("No direct bus was found. You can take a 2-bus route:"))
        assertTrue("Must have Step 1", answer.contains("1. Board Bus "))
        assertTrue("Must have Step 2 with change", answer.contains("2. At ") && answer.contains("change to Bus "))
    }

    // =========================================================================
    // Test 5 — Bus transfer: Poonamallee -> Chennai Rail Museum
    // =========================================================================
    @Test
    fun test05_BusTransfer_PoonamalleeToChennaiRailMuseum() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Poonamallee",
            destination = "Chennai Rail Museum",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertTrue("Must show 2-bus route transfer", answer.startsWith("No direct bus was found. You can take a 2-bus route:"))
        assertTrue("Must have Step 1", answer.contains("1. Board Bus "))
        assertTrue("Must have Step 2 with change", answer.contains("2. At ") && answer.contains("change to Bus "))
    }

    // =========================================================================
    // Test 6 — Metro regression: Vadapalani -> Chennai Central
    // =========================================================================
    @Test
    fun test06_MetroRegression_VadapalaniToCentral() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Vadapalani",
            destination = "Chennai Central",
            mode = "subway"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertFalse("Must NOT use transfer wording for metro", answer.contains("No direct bus was found", ignoreCase = true))
        assertTrue("Must show verified direct routes for subway", answer.contains("verified direct routes from Vadapalani to Chennai Central"))
        assertTrue("Must show Metro line route", answer.contains("Metro", ignoreCase = true) || answer.contains("CMRL", ignoreCase = true))
    }

    // =========================================================================
    // Test 7 — No route: Atlantis City -> Valluvar Kottam
    // =========================================================================
    @Test
    fun test07_NoRoute_AtlantisCityToValluvarKottam() = runBlocking {
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_SEARCH,
            origin = "Atlantis City",
            destination = "Valluvar Kottam",
            mode = "bus"
        )
        val answer = QueryProcessor.processTransportQuery(intent, repo)
        assertTrue("Answer must not be blank", answer.isNotBlank())
        assertFalse("Must NOT invent transfer route", answer.contains("No direct bus was found. You can take a 2-bus route:"))
        assertTrue("Must display honest no-route message", answer.contains("couldn't find a verified direct route") || answer.contains("no routes matching"))
    }

    // =========================================================================
    // Test 8 — Tanglish regression: Marina beach ku bus la epdi poganum?
    // =========================================================================
    @Test
    fun test08_TanglishRegression_BusIntentDetection() {
        val q = "Marina beach ku bus la epdi poganum?"
        val intent = QueryProcessor.detectTransportIntent(q)
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, intent.subIntent)
        assertEquals("bus", intent.mode)
        assertEquals("Marina beach", intent.destination)
    }
}
