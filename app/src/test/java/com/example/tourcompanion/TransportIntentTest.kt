package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for Transport Intent Detection, Sub-intents,
 * Transport Answer Construction, and Non-transport Regressions.
 */
class TransportIntentTest {

    // =========================================================================
    // 1. Transport Intent & Sub-intent Classification Tests
    // =========================================================================

    @Test
    fun testNearestBusStopIntent() {
        val intent = QueryProcessor.detectTransportIntent("Where is the nearest bus stop?")
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, intent.subIntent)
        assertEquals("bus", intent.mode)
    }

    @Test
    fun testMetroNearMeIntent() {
        val intent = QueryProcessor.detectTransportIntent("Metro near me")
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, intent.subIntent)
        assertEquals("subway", intent.mode)
    }

    @Test
    fun testWhatIsRoute5CIntent() {
        val intent = QueryProcessor.detectTransportIntent("What is route 5C?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, intent.subIntent)
        assertEquals("5C", intent.target)
    }

    @Test
    fun testTellMeAbout21GIntent() {
        val intent = QueryProcessor.detectTransportIntent("Tell me about 21G")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, intent.subIntent)
        assertEquals("21G", intent.target)
    }

    @Test
    fun testBlueLineMetroIntent() {
        val intent = QueryProcessor.detectTransportIntent("Blue Line metro")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, intent.subIntent)
        assertEquals("Blue", intent.target)
        assertEquals("subway", intent.mode)
    }

    @Test
    fun testWhatBusesStopAtBroadwayIntent() {
        val intent = QueryProcessor.detectTransportIntent("What buses stop at Broadway?")
        assertEquals(TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP, intent.subIntent)
        assertEquals("Broadway", intent.target)
    }

    @Test
    fun testWhichRoutesServeStopIntent() {
        val intent = QueryProcessor.detectTransportIntent("Which routes serve MTC-840?")
        assertEquals(TransportSubIntent.TRANSPORT_STOP_ROUTE_LOOKUP, intent.subIntent)
        assertEquals("MTC-840", intent.target)
    }

    @Test
    fun testAdditionalNearbyVariants() {
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Bus stop near me").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("How do I find a bus?").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Where can I catch a bus near Central?").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Show buses near me").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("What buses are available near Marina?").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Is there a metro near me?").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Where is the nearest metro?").subIntent)
    }

    @Test
    fun testAdditionalRouteVariants() {
        val blue = QueryProcessor.detectTransportIntent("Tell me about the Blue Line")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, blue.subIntent)
        assertEquals("Blue", blue.target)

        val green = QueryProcessor.detectTransportIntent("Green Line")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, green.subIntent)
        assertEquals("Green", green.target)

        val route5C = QueryProcessor.detectTransportIntent("Tell me about 5C")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, route5C.subIntent)
        assertEquals("5C", route5C.target)

        val route21G = QueryProcessor.detectTransportIntent("What is route 21G?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, route21G.subIntent)
        assertEquals("21G", route21G.target)
    }

    // =========================================================================
    // 2. Non-Transport & Regression Intent Tests
    // =========================================================================

    @Test
    fun testDestinationQuestionsAreNotTransport() {
        val marina = QueryProcessor.detectTransportIntent("Tell me about Marina Beach")
        assertEquals(TransportSubIntent.NONE, marina.subIntent)

        val kapaleeshwarar = QueryProcessor.detectTransportIntent("Arulmigu Kapaleeshwarar Temple timings")
        assertEquals(TransportSubIntent.NONE, kapaleeshwarar.subIntent)

        val reach = QueryProcessor.detectTransportIntent("How do I reach Marina?")
        assertEquals(TransportSubIntent.NONE, reach.subIntent)

        val history = QueryProcessor.detectTransportIntent("What is the history of Central?")
        assertEquals(TransportSubIntent.NONE, history.subIntent)

        val chennai = QueryProcessor.detectTransportIntent("What can I do in Chennai?")
        assertEquals(TransportSubIntent.NONE, chennai.subIntent)
    }

    @Test
    fun testSafetyQuestionsAreNotTransport() {
        val safeAtNight = QueryProcessor.detectTransportIntent("Is Marina safe at night?")
        assertEquals(TransportSubIntent.NONE, safeAtNight.subIntent)

        val atm = QueryProcessor.detectTransportIntent("ATM safety tips")
        assertEquals(TransportSubIntent.NONE, atm.subIntent)
    }

    @Test
    fun testEmergencyAndDistressNeverClassifiedAsTransport() {
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("SOS").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("help").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("Call 112").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("Someone is following me near the bus stop").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("I was robbed on the bus").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("Accident! Injured on road").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("காப்பாற்றுங்கள்").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("मदद बचाओ").subIntent)
    }

    @Test
    fun testSmallTalkAndEmptyQueryNotTransport() {
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("   ").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("hi").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("hello").subIntent)
        assertEquals(TransportSubIntent.NONE, QueryProcessor.detectTransportIntent("who are you").subIntent)
    }

    // =========================================================================
    // 3. Transport Answer Formatting & Execution Tests
    // =========================================================================

    private val testBaseUrl = "http://localhost/tourcompanion/api/"
    private val transportRepo = TransportRepository(api = TransportApiClient(testBaseUrl))

    @Test
    fun testStopToRouteLimitationAnswer() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("What buses stop at Broadway?")
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("route-to-stop mapping"))
        assertTrue(answer.contains("not available in the current static dataset"))
        // Confirms it never guesses or claims specific routes
        assertFalse(answer.contains("The next bus is"))
    }

    @Test
    fun testNearbyStopsWithoutCoordinatesRequiresLocation() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("Where is the nearest bus stop?")
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo, userLat = null, userLon = null)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("need your location coordinates"))
    }

    @Test
    fun testNearbyStopsWithCoordinatesReturnsFormattedStops() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("Where is the nearest bus stop?")
        // Central coordinates
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo, userLat = 13.0814, userLon = 80.2729)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("nearby transport stops include:"))
        assertTrue(answer.contains("km away"))
        assertTrue(answer.contains("Distances are calculated straight-line estimates"))
        // Confirms no live tracking claims
        assertFalse(answer.contains("The next bus is"))
        assertFalse(answer.contains("will arrive in"))
    }

    @Test
    fun testRouteSearch5CAnswerFormatting() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("What is route 5C?")
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("matching routes for '5C':"))
        assertTrue(answer.contains("Route 5C:"))
        assertTrue(answer.contains("Bus"))
        assertTrue(answer.contains("Showing 5 of"))
        // Confirms no live claims
        assertFalse(answer.contains("approaching"))
        assertFalse(answer.contains("live travel"))
    }

    @Test
    fun testRouteSearchBlueLineAnswerFormatting() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("Tell me about the Blue Line")
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("matching routes for 'Blue':"))
        assertTrue(answer.contains("Metro"))
        assertTrue(answer.contains("Chennai Metro Rail Limited"))
    }

    @Test
    fun testRouteSearchNonExistentAnswerFormatting() = runBlocking {
        val intent = TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = "NONEXISTENT_9999")
        val answer = QueryProcessor.processTransportQuery(intent, transportRepo)

        assertTrue(answer.contains("Based on the available Chennai transport data"))
        assertTrue(answer.contains("no routes matching 'NONEXISTENT_9999' were found"))
    }

    @Test
    fun testOfflineTransportHandling() = runBlocking {
        val unreachableRepo = TransportRepository(api = TransportApiClient("http://127.0.0.1:59999/api/"))
        val intent = TransportIntent(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, target = "5C")
        val answer = QueryProcessor.processTransportQuery(intent, unreachableRepo)

        assertTrue(answer.contains("I can't access the transport database right now"))
    }

    // =========================================================================
    // 4. Phase 5: Location Validation & Permission Requirement Tests
    // =========================================================================

    @Test
    fun testOnlyNearbyIntentRequiresLocation() {
        // Queries that MUST NOT require location
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("What is route 5C?").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Tell me about the Blue Line").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("What buses stop at Broadway?").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Tell me about Marina Beach").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Is Marina safe at night?").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Help! Stalked near bus stop").subIntent)
        assertNotEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("hi").subIntent)

        // Queries that DO require location
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Where is the nearest bus stop?").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Metro near me").subIntent)
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, QueryProcessor.detectTransportIntent("Bus stop near me").subIntent)
    }

    @Test
    fun testCoordinateValidationLogic() {
        // Valid Chennai coordinates
        assertTrue(AppLocationManager.isValidCoordinate(13.0827, 80.2707))
        assertTrue(AppLocationManager.isValidCoordinate(13.0475, 80.2824))

        // Invalid coordinates
        assertFalse("NaN latitude must be rejected", AppLocationManager.isValidCoordinate(Double.NaN, 80.2707))
        assertFalse("NaN longitude must be rejected", AppLocationManager.isValidCoordinate(13.0827, Double.NaN))
        assertFalse("Infinite latitude must be rejected", AppLocationManager.isValidCoordinate(Double.POSITIVE_INFINITY, 80.2707))
        assertFalse("Infinite longitude must be rejected", AppLocationManager.isValidCoordinate(13.0827, Double.NEGATIVE_INFINITY))
        assertFalse("Out-of-range latitude must be rejected", AppLocationManager.isValidCoordinate(95.0, 80.2707))
        assertFalse("Out-of-range longitude must be rejected", AppLocationManager.isValidCoordinate(13.0827, 195.0))
        assertFalse("Null Island (0,0) must be rejected", AppLocationManager.isValidCoordinate(0.0, 0.0))
    }
}
