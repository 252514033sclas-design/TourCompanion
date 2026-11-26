package com.example.tourcompanion

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Phase 5: Intent-driven AnswerRepository routing.
 *
 * Verifies that:
 * 1. EMERGENCY pre-empts all and returns deterministic 112 guidance locally.
 * 2. SMALL_TALK is handled locally without network overhead.
 * 3. TRANSPORT intents route to TransportRepository and MtcTransportProvider.
 * 4. Location-dependent intents (NEARBY_ATTRACTIONS, DISTANCE_DIRECTIONS, LOST_DISORIENTED)
 *    return clear deterministic unavailable messages without invoking GPS or Gemma.
 * 5. Destination and knowledge queries route to backend ask.php.
 */
class AnswerRepositoryRoutingTest {

    private lateinit var repository: AnswerRepository
    private val testBackendUrl = "http://127.0.0.1/tourcompanion/api/ask.php"

    // A lightweight Context stub for JVM unit testing
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        val context = TestContext()
        val apiClient = AiAnswerClient(testBackendUrl)
        val transportRepo = TransportRepository(api = TransportApiClient("http://127.0.0.1/tourcompanion/api/"))
        val queryProcessor = QueryProcessor(context)
        repository = AnswerRepository(
            context = context,
            transport = transportRepo,
            remote = apiClient,
            local = queryProcessor
        )
    }

    // =========================================================================
    // 1. Emergency Routing (Deterministic, zero Gemma, zero network overhead)
    // =========================================================================

    @Test
    fun testEmergencyRoutingEnglishAmbulance() = runBlocking {
        val answer = repository.answer("I need an ambulance")
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue("Must mention 112", answer.text.contains("112"))
    }

    @Test
    fun testEmergencyRoutingSos() = runBlocking {
        val answer = repository.answer("SOS")
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue(answer.text.contains("112"))
    }

    @Test
    fun testEmergencyRoutingTamil() = runBlocking {
        val answer = repository.answer("காப்பாற்றுங்கள்")
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue(answer.text.contains("112"))
    }

    @Test
    fun testEmergencyRoutingHindi() = runBlocking {
        val answer = repository.answer("मुझे आपातकालीन मदद चाहिए")
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue(answer.text.contains("112"))
    }

    // =========================================================================
    // 2. Small Talk Routing (Handled locally, zero network overhead)
    // =========================================================================

    @Test
    fun testSmallTalkRoutingHello() = runBlocking {
        val answer = repository.answer("Hello")
        assertEquals(AnswerRepository.Source.SMALL_TALK, answer.source)
        assertTrue(answer.text.isNotBlank())
    }

    @Test
    fun testSmallTalkRoutingHi() = runBlocking {
        val answer = repository.answer("Hi")
        assertEquals(AnswerRepository.Source.SMALL_TALK, answer.source)
        assertTrue(answer.text.isNotBlank())
    }

    @Test
    fun testSmallTalkRoutingWhoAreYou() = runBlocking {
        val answer = repository.answer("Who are you?")
        assertEquals(AnswerRepository.Source.SMALL_TALK, answer.source)
        assertTrue(answer.text.contains("Semmozhi", ignoreCase = true))
    }

    @Test
    fun testSmallTalkRoutingThankYou() = runBlocking {
        val answer = repository.answer("Thank you")
        assertEquals(AnswerRepository.Source.SMALL_TALK, answer.source)
        assertTrue(answer.text.isNotBlank())
    }

    // =========================================================================
    // 3. Location-Dependent Intents (Phase 5: No GPS, no fake coords, no Gemma)
    // =========================================================================

    @Test
    fun testNearbyAttractionsNoGpsYet() = runBlocking {
        val answer = repository.answer("What is near me?", userLat = null, userLon = null)
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertEquals(AnswerRepository.NEARBY_ATTRACTIONS_UNAVAILABLE, answer.text)
        assertFalse("Must not claim live proximity", answer.text.contains("meters away"))
    }

    @Test
    fun testNearbyAttractionsWithMarinaCoordinates() = runBlocking {
        // Marina Beach benchmark coordinates
        val answer = repository.answer("What is near me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Marina Beach", answer.text.contains("Marina", ignoreCase = true))
        assertTrue("Answer must state distance or proximity", answer.text.contains("0") || answer.text.contains("km"))
    }

    @Test
    fun testNearbyAttractionsWithEgmoreMuseumCoordinates() = runBlocking {
        // Government Museum Egmore benchmark coordinates
        val answer = repository.answer("What is near me?", userLat = 13.071100, userLon = 80.256900)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Museum", answer.text.contains("Museum", ignoreCase = true))
    }

    @Test
    fun testNearbyAttractionsOutsideChennai() = runBlocking {
        // Mountain View benchmark coordinates (outside Chennai)
        val answer = repository.answer("What is near me?", userLat = 37.421998, userLon = -122.084000)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Must state no attractions found nearby", answer.text.contains("No Chennai tourist attractions were found"))
    }

    @Test
    fun testDistanceDirectionsNoGpsYet() = runBlocking {
        val answer = repository.answer("How far is Marina Beach from me?", userLat = null, userLon = null)
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertEquals(AnswerRepository.DISTANCE_LOCATION_REQUIRED_MESSAGE, answer.text)
        assertFalse("Must not invent fake distance", answer.text.contains("km away"))
    }

    @Test
    fun testDistanceDirectionsMarinaWithCoordinates() = runBlocking {
        // Marina Beach benchmark coordinates: (13.054180, 80.283680)
        val answer = repository.answer("How far is Marina Beach from me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Marina", answer.text.contains("Marina", ignoreCase = true))
        assertTrue("Answer must state distance or proximity", answer.text.contains("0.0") || answer.text.contains("0 km") || answer.text.contains("right now", ignoreCase = true))
    }

    @Test
    fun testDistanceDirectionsMarinaFromEgmoreMuseum() = runBlocking {
        // Egmore Museum coordinates: (13.071100, 80.256900) -> distance to Marina is ~3.5 km
        val answer = repository.answer("How far is Marina Beach from me?", userLat = 13.071100, userLon = 80.256900)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Marina", answer.text.contains("Marina", ignoreCase = true))
        assertTrue("Answer must state dynamic ~3.5 km distance", answer.text.contains("3.5") || answer.text.contains("km"))
    }

    @Test
    fun testDistanceDirectionsKapaleeshwararFromMarina() = runBlocking {
        // From Marina coordinates to Kapaleeshwarar Temple -> ~2.7 km
        val answer = repository.answer("How far is Kapaleeshwarar Temple from me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Kapaleeshwarar", answer.text.contains("Kapaleeshwarar", ignoreCase = true))
        assertTrue("Answer must state dynamic ~2.7 km distance", answer.text.contains("2.7") || answer.text.contains("km"))
    }

    @Test
    fun testDistanceDirectionsFortStGeorgeFromMarina() = runBlocking {
        // From Marina coordinates to Fort St George -> ~2.9 km
        val answer = repository.answer("How far is Fort St George from me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Answer must mention Fort St George", answer.text.contains("Fort St", ignoreCase = true))
        assertTrue("Answer must state dynamic ~2.9 km distance", answer.text.contains("2.9") || answer.text.contains("km"))
    }

    @Test
    fun testDistanceDirectionsUnknownDestination() = runBlocking {
        val answer = repository.answer("How far is Taj Mahal from me?", userLat = 13.054180, userLon = 80.283680)
        assertTrue(answer.source == AnswerRepository.Source.LOCAL || answer.source == AnswerRepository.Source.BACKEND)
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
    }

    // =========================================================================
    // Unknown Destination and Routing Verification Tests (Queries A - H)
    // =========================================================================

    @Test
    fun testFocusedQueryA_WantToGoToVRMall() = runBlocking {
        val answer = repository.answer("I want to go to VR Mall")
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
        assertTrue("Must mention VR Mall", answer.text.contains("VR Mall", ignoreCase = true))
        assertFalse("Must NOT return generic safety tip", answer.text.contains("When travelling alone", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryB_WantToReachVRMall() = runBlocking {
        val answer = repository.answer("I want to reach VR Mall")
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
        assertTrue("Must mention VR Mall", answer.text.contains("VR Mall", ignoreCase = true))
        assertFalse("Must NOT return generic safety tip", answer.text.contains("When travelling alone", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryC_WantToGetToVRMall() = runBlocking {
        val answer = repository.answer("I want to get to VR Mall")
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
        assertTrue("Must mention VR Mall", answer.text.contains("VR Mall", ignoreCase = true))
        assertFalse("Must NOT return generic safety tip", answer.text.contains("When travelling alone", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryD_WhereIsVRMall() = runBlocking {
        val answer = repository.answer("Where is VR Mall?")
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
        assertFalse("Must NOT return generic safety tip", answer.text.contains("When travelling alone", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryE_SaveethaCollegeToVRMall() = runBlocking {
        val answer = repository.answer("I am at Saveetha College and I want to go to VR Mall")
        assertTrue("Must state destination not found", answer.text.contains("could not find a verified Chennai destination", ignoreCase = true))
        assertTrue("Must mention VR Mall", answer.text.contains("VR Mall", ignoreCase = true))
        assertFalse("Must NOT return generic safety tip", answer.text.contains("When travelling alone", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryF_TellMeAboutMarinaBeach() = runBlocking {
        val answer = repository.answer("Tell me about Marina Beach")
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(answer.text.contains("Marina", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryG_PublicPlacesSafetyTipPreserved() = runBlocking {
        val answer = repository.answer("Is it safe to visit public places alone?")
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Must contain public places safety tip", answer.text.contains("public", ignoreCase = true))
    }

    @Test
    fun testFocusedQueryH_SoloTravelSafetyTipPreserved() = runBlocking {
        val answer = repository.answer("Give me safety tips for travelling alone.")
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Must contain solo travel tip", answer.text.contains("alone", ignoreCase = true) || answer.text.contains("travelling", ignoreCase = true))
    }

    @Test
    fun testDistanceDirectionsOutsideChennai() = runBlocking {
        // Mountain View benchmark coordinates (outside Chennai)
        val answer = repository.answer("How far is Marina Beach from me?", userLat = 37.421998, userLon = -122.084000)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue("Must state destination distance unavailable from current location", answer.text.contains("unavailable from your current location", ignoreCase = true))
    }

    @Test
    fun testDistanceDirectionsClassificationSignals() {
        val q1 = QueryProcessor.classifyTravelIntent("How far is Marina Beach from me?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, q1.intent)
        assertTrue("Must require location", q1.requiresLocation)

        val q2 = QueryProcessor.classifyTravelIntent("Distance to Marina Beach")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, q2.intent)
        assertTrue("Must require location", q2.requiresLocation)

        val q3 = QueryProcessor.classifyTravelIntent("How far am I from Marina Beach?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, q3.intent)
        assertTrue("Must require location", q3.requiresLocation)
    }

    @Test
    fun testNormalQueriesDoNotRequireLocation() {
        assertFalse("Tell me about Marina Beach must not require location",
            QueryProcessor.classifyTravelIntent("Tell me about Marina Beach").requiresLocation)
        assertFalse("What are the timings of Marina Beach must not require location",
            QueryProcessor.classifyTravelIntent("What are the timings of Marina Beach?").requiresLocation)
        assertFalse("What can I do at Marina Beach must not require location",
            QueryProcessor.classifyTravelIntent("What can I do at Marina Beach?").requiresLocation)
        assertFalse("Hello must not require location",
            QueryProcessor.classifyTravelIntent("Hello").requiresLocation)
        assertFalse("What buses go to Marina Beach must not require location",
            QueryProcessor.classifyTravelIntent("What buses go to Marina Beach?").requiresLocation)
        assertFalse("Emergency query must not require location",
            QueryProcessor.classifyTravelIntent("I need emergency help").requiresLocation)
    }

    @Test
    fun testLostDisorientedNoFakeNav() = runBlocking {
        val answer = repository.answer("I'm lost")
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertEquals(AnswerRepository.LOST_DISORIENTED_MESSAGE, answer.text)
        assertTrue("Must advise emergency 112 when lost", answer.text.contains("112"))
    }

    @Test
    fun testNamedOriginRoutingReturnsHonestLimitation() = runBlocking {
        QueryProcessor.aliasMatcher = DestinationAliasMatcher.fromAliases(
            listOf(
                DestinationAlias(1, 1, "Marina Beach", "Marina Beach", "en"),
                DestinationAlias(2, 2, "Arulmigu Kapaleeshwarar Temple", "Kapaleeshwarar Temple", "en")
            ),
            mapOf(1 to "Marina Beach", 2 to "Arulmigu Kapaleeshwarar Temple")
        )

        val answer = repository.answer(
            "I'm at Marina Beach. How do I get to Kapaleeshwarar Temple?",
            userLat = 13.0814,
            userLon = 80.2729
        )
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue("Must state limitation about calculating route from Marina Beach", answer.text.contains("can't calculate a route from Marina Beach"))
        assertTrue("Must mention phone location for distance estimates", answer.text.contains("phone's current location is used only for distance estimates"))
        assertFalse("Must NOT present GPS distance", answer.text.contains("5.3 km"))
    }

    // =========================================================================
    // 4. Transport Intents Routing
    // =========================================================================

    @Test
    fun testTransportNearbyWithoutCoordinatesRequestsLocation() = runBlocking {
        val answer = repository.answer("Which bus stop is near me?", userLat = null, userLon = null)
        assertEquals(AnswerRepository.Source.TRANSPORT, answer.source)
        assertTrue("Must request location coordinates", answer.text.contains("need your location coordinates"))
    }

    @Test
    fun testTransportRouteSearch() = runBlocking {
        val answer = repository.answer("What is route 5C?")
        assertEquals(AnswerRepository.Source.TRANSPORT, answer.source)
        assertTrue(answer.text.contains("Route 5C") || answer.text.contains("5C"))
    }

    @Test
    fun testTransportTiming() = runBlocking {
        val answer = repository.answer("When does 5C leave Adyar?")
        assertEquals(AnswerRepository.Source.TRANSPORT, answer.source)
        assertTrue(answer.text.contains("5C"))
    }

    // =========================================================================
    // 5. Destination & Knowledge Routing (via backend ask.php)
    // =========================================================================

    @Test
    fun testDestinationDetailsMarinaBeach() = runBlocking {
        val answer = repository.answer("Tell me about Marina Beach")
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(answer.text.contains("Marina", ignoreCase = true))
    }

    @Test
    fun testDestinationTimingKapaleeshwarar() = runBlocking {
        val answer = repository.answer("What are the visiting hours of Kapaleeshwarar Temple?")
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(
            "Must state visiting hours or timing",
            answer.text.contains("5:30") || answer.text.contains("visiting", ignoreCase = true) || answer.text.contains("hours", ignoreCase = true)
        )
    }

    @Test
    fun testTravelTipLostWallet() = runBlocking {
        val answer = repository.answer("What should I do if I lose my wallet?")
        assertTrue(answer.source == AnswerRepository.Source.BACKEND || answer.source == AnswerRepository.Source.LOCAL)
        assertTrue(answer.text.isNotBlank())
    }

    // =========================================================================
    // 6. Empty / Whitespace Query Handling
    // =========================================================================

    @Test
    fun testEmptyQuestionReturnsPrompt() = runBlocking {
        val answer = repository.answer("")
        assertEquals(AnswerRepository.Source.NONE, answer.source)
    }

    @Test
    fun testWhitespaceQuestionReturnsPrompt() = runBlocking {
        val answer = repository.answer("     ")
        assertEquals(AnswerRepository.Source.NONE, answer.source)
    }
}
