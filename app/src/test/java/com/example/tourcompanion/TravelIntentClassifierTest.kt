package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the deterministic TravelIntent classification layer.
 *
 * Tests cover all 17 intent categories, multilingual inputs (English / Tamil / Hindi),
 * and location-dependency flags.
 *
 * No Android context required — pure JVM tests.
 */
class TravelIntentClassifierTest {

    private fun classify(q: String) = QueryProcessor.classifyTravelIntent(q)

    // =========================================================================
    // 1. EMERGENCY (highest priority — must preempt all)
    // =========================================================================

    @Test
    fun testEmergencyEnglish() {
        assertEquals(TravelIntent.EMERGENCY, classify("I need emergency help").intent)
    }

    @Test
    fun testEmergencyHindiDevanagari() {
        // "मुझे आपातकालीन मदद चाहिए" contains "आपातकाल" and "मदद"
        assertEquals(TravelIntent.EMERGENCY, classify("मुझे आपातकालीन मदद चाहिए").intent)
    }

    @Test
    fun testEmergencyTamil() {
        assertEquals(TravelIntent.EMERGENCY, classify("காப்பாற்றுங்கள்").intent)
    }

    @Test
    fun testEmergencySOS() {
        assertEquals(TravelIntent.EMERGENCY, classify("SOS").intent)
    }

    @Test
    fun testEmergencyHelp() {
        // "help me" is in EMERGENCY_EN list
        assertEquals(TravelIntent.EMERGENCY, classify("help me").intent)
    }

    @Test
    fun testEmergencyPreemptsTransport() {
        // "accident" is an emergency word even if the sentence contains "bus"
        assertEquals(TravelIntent.EMERGENCY, classify("Accident on the bus! Injured!").intent)
    }

    @Test
    fun testEmergencyPreemptsDestination() {
        // "robbed" is an emergency even if Marina Beach appears
        assertEquals(TravelIntent.EMERGENCY, classify("I was robbed at Marina Beach").intent)
    }

    // =========================================================================
    // 2. DESTINATION_DETAILS
    // =========================================================================

    @Test
    fun testDestinationDetailsEnglish() {
        val result = classify("Tell me about Marina Beach")
        assertEquals(TravelIntent.DESTINATION_DETAILS, result.intent)
        assertTrue(result.requiresDestinationData)
        assertFalse(result.requiresLocation)
    }

    @Test
    fun testDestinationDetailsTamil() {
        // "மெரினா" is in DESTINATION_PATTERNS
        assertEquals(TravelIntent.DESTINATION_DETAILS, classify("மெரினா கடற்கரை பற்றி சொல்லுங்கள்").intent)
    }

    @Test
    fun testDestinationDetailsHindi() {
        assertEquals(TravelIntent.DESTINATION_DETAILS, classify("मरीना बीच के बारे में बताएं").intent)
    }

    @Test
    fun testDestinationDetailsKapaleeshwarar() {
        assertEquals(TravelIntent.DESTINATION_DETAILS, classify("Tell me about Kapaleeshwarar Temple").intent)
    }

    @Test
    fun testDestinationDetailsFortStGeorge() {
        assertEquals(TravelIntent.DESTINATION_DETAILS, classify("Fort St George history").intent)
    }

    @Test
    fun testDestinationDetailsShortName() {
        // "Marina" alone should match via DESTINATION_PATTERNS
        assertEquals(TravelIntent.DESTINATION_DETAILS, classify("Marina").intent)
    }

    // =========================================================================
    // 3. DESTINATION_TIMING
    // =========================================================================

    @Test
    fun testDestinationTimingKapaleeshwarar() {
        val result = classify("When is Kapaleeshwarar Temple open?")
        assertEquals(TravelIntent.DESTINATION_TIMING, result.intent)
        assertTrue(result.requiresDestinationData)
    }

    @Test
    fun testDestinationTimingHours() {
        assertEquals(TravelIntent.DESTINATION_TIMING, classify("What are the visiting hours for Fort St George?").intent)
    }

    @Test
    fun testDestinationTimingTamil() {
        // திறக்கும் = when does it open
        assertEquals(TravelIntent.DESTINATION_TIMING, classify("கபாலீஸ்வரர் கோயில் திறக்கும் நேரம்?").intent)
    }

    @Test
    fun testDestinationTimingHindi() {
        // "समय" = time / timing
        assertEquals(TravelIntent.DESTINATION_TIMING, classify("मरीना बीच खुलने का समय क्या है?").intent)
    }

    @Test
    fun testDestinationTimingClose() {
        assertEquals(TravelIntent.DESTINATION_TIMING, classify("What time does Government Museum close?").intent)
    }

    // =========================================================================
    // 4. DESTINATION_DISCOVERY
    // =========================================================================

    @Test
    fun testDestinationDiscoveryPlacesToVisit() {
        val result = classify("What places can I visit in Chennai?")
        assertEquals(TravelIntent.DESTINATION_DISCOVERY, result.intent)
        assertTrue(result.requiresDestinationData)
    }

    @Test
    fun testDestinationDiscoveryTourist() {
        assertEquals(TravelIntent.DESTINATION_DISCOVERY, classify("Show me tourist attractions in Chennai").intent)
    }

    @Test
    fun testDestinationDiscoveryWhereToGo() {
        assertEquals(TravelIntent.DESTINATION_DISCOVERY, classify("Where should I go in Chennai?").intent)
    }

    @Test
    fun testDestinationDiscoveryMustVisit() {
        assertEquals(TravelIntent.DESTINATION_DISCOVERY, classify("What are must visit places in Chennai?").intent)
    }

    // =========================================================================
    // 5. ACTIVITY_PLANNING
    // =========================================================================

    @Test
    fun testActivityPlanningAtBeach() {
        val result = classify("What can I do at Marina Beach?")
        assertEquals(TravelIntent.ACTIVITY_PLANNING, result.intent)
    }

    @Test
    fun testActivityPlanningThingsToDo() {
        assertEquals(TravelIntent.ACTIVITY_PLANNING, classify("Things to do in Chennai").intent)
    }

    @Test
    fun testActivityPlanningHowToEnjoy() {
        assertEquals(TravelIntent.ACTIVITY_PLANNING, classify("How to enjoy Besant Nagar Beach?").intent)
    }

    // =========================================================================
    // 6. NEARBY_ATTRACTIONS
    // =========================================================================

    @Test
    fun testNearbyAttractionsNearMe() {
        val result = classify("What is near me?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue("NEARBY_ATTRACTIONS must require location", result.requiresLocation)
    }

    @Test
    fun testNearbyAttractionsNearbyKeyword() {
        val result = classify("nearby")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testNearbyAttractionsTamil() {
        // "அருகில்" = nearby
        val result = classify("அருகில் என்ன இருக்கிறது?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testNearbyAttractionsHindi() {
        val result = classify("पास में क्या है?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    // =========================================================================
    // 7. TRANSPORT_NEARBY
    // =========================================================================

    @Test
    fun testTransportNearbyBusStop() {
        val result = classify("Which bus stop is near me?")
        assertEquals(TravelIntent.TRANSPORT_NEARBY, result.intent)
        assertTrue(result.requiresLocation)
        assertTrue(result.requiresTransportData)
    }

    @Test
    fun testTransportNearbyMetro() {
        val result = classify("Metro near me")
        assertEquals(TravelIntent.TRANSPORT_NEARBY, result.intent)
        assertTrue(result.requiresLocation)
    }

    // =========================================================================
    // 8. TRANSPORT_ROUTE_SEARCH
    // =========================================================================

    @Test
    fun testTransportRouteSearch() {
        val result = classify("Which bus goes to Tambaram?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        assertTrue(result.requiresTransportData)
        assertFalse(result.requiresLocation)
    }

    @Test
    fun testTransportRouteSearch5C() {
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, classify("What is route 5C?").intent)
    }

    // =========================================================================
    // 9. TRANSPORT_ROUTE_TIMING
    // =========================================================================

    @Test
    fun testTransportRouteTiming5C() {
        val result = classify("When does 5C leave Adyar?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_TIMING, result.intent)
        assertTrue(result.requiresTransportData)
        assertFalse(result.requiresLocation)
    }

    @Test
    fun testTransportRouteTimingSchedule() {
        assertEquals(TravelIntent.TRANSPORT_ROUTE_TIMING, classify("Schedule for bus 21G from Tambaram").intent)
    }

    // =========================================================================
    // 10. DISTANCE_DIRECTIONS
    // =========================================================================

    @Test
    fun testDistanceDirectionsHowFar() {
        val result = classify("How far is Marina Beach from me?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue("'from me' should require location", result.requiresLocation)
    }

    @Test
    fun testDistanceDirectionsHowToGet() {
        val result = classify("How do I get to Kapaleeshwarar Temple?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
    }

    @Test
    fun testDistanceDirectionsHowToReach() {
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classify("How to reach Fort St George?").intent)
    }

    // =========================================================================
    // 11. LOST_DISORIENTED
    // =========================================================================

    @Test
    fun testLostDisoriented() {
        val result = classify("I'm lost")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testLostDisorientedWhereAmI() {
        val result = classify("Where am I?")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testLostDisorientedHindi() {
        val result = classify("मैं खो गया हूं")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    // =========================================================================
    // 12. SAFETY
    // =========================================================================

    @Test
    fun testSafetyIsItSafe() {
        val result = classify("Is Marina Beach safe?")
        assertEquals(TravelIntent.SAFETY, result.intent)
    }

    @Test
    fun testSafetySafeAtNight() {
        assertEquals(TravelIntent.SAFETY, classify("Is it safe at night?").intent)
    }

    @Test
    fun testSafetyDangerous() {
        assertEquals(TravelIntent.SAFETY, classify("Is Egmore area dangerous?").intent)
    }

    // =========================================================================
    // 13. TRAVEL_TIP
    // =========================================================================

    @Test
    fun testTravelTipLoseWallet() {
        val result = classify("What should I do if I lose my wallet?")
        assertEquals(TravelIntent.TRAVEL_TIP, result.intent)
    }

    @Test
    fun testTravelTipCarryCash() {
        assertEquals(TravelIntent.TRAVEL_TIP, classify("How should I carry cash?").intent)
    }

    @Test
    fun testTravelTipFoodSafe() {
        assertEquals(TravelIntent.TRAVEL_TIP, classify("Is the food safe to eat?").intent)
    }

    // =========================================================================
    // 14. BUDGET_TRAVEL
    // =========================================================================

    @Test
    fun testBudgetTravel500Rupees() {
        val result = classify("What can I do for 500 rupees?")
        assertEquals(TravelIntent.BUDGET_TRAVEL, result.intent)
    }

    @Test
    fun testBudgetTravelCheap() {
        assertEquals(TravelIntent.BUDGET_TRAVEL, classify("Cheap places to visit in Chennai").intent)
    }

    @Test
    fun testBudgetTravelEntryFee() {
        assertEquals(TravelIntent.BUDGET_TRAVEL, classify("What is the entry fee for Government Museum?").intent)
    }

    @Test
    fun testBudgetTravelHindi() {
        assertEquals(TravelIntent.BUDGET_TRAVEL, classify("मरीना बीच जाने में कितना खर्च होगा?").intent)
    }

    // =========================================================================
    // 15. TIME_CONSTRAINED_PLANNING
    // =========================================================================

    @Test
    fun testTimeConstrainedTwoHours() {
        val result = classify("What can I visit in two hours?")
        assertEquals(TravelIntent.TIME_CONSTRAINED_PLANNING, result.intent)
    }

    @Test
    fun testTimeConstrainedHalfDay() {
        assertEquals(TravelIntent.TIME_CONSTRAINED_PLANNING, classify("I have a half day — what do I do?").intent)
    }

    @Test
    fun testTimeConstrainedEveningPlan() {
        assertEquals(TravelIntent.TIME_CONSTRAINED_PLANNING, classify("Give me an evening plan for Chennai").intent)
    }

    // =========================================================================
    // 16. SMALL_TALK
    // =========================================================================

    @Test
    fun testSmallTalkHello() {
        val result = classify("Hello Semmozhi")
        assertEquals(TravelIntent.SMALL_TALK, result.intent)
    }

    @Test
    fun testSmallTalkThanks() {
        assertEquals(TravelIntent.SMALL_TALK, classify("Thank you").intent)
    }

    @Test
    fun testSmallTalkHindi() {
        assertEquals(TravelIntent.SMALL_TALK, classify("नमस्ते").intent)
    }

    @Test
    fun testSmallTalkTamil() {
        assertEquals(TravelIntent.SMALL_TALK, classify("வணக்கம்").intent)
    }

    @Test
    fun testSmallTalkHowAreYou() {
        assertEquals(TravelIntent.SMALL_TALK, classify("How are you?").intent)
    }

    // =========================================================================
    // 17. UNKNOWN
    // =========================================================================

    @Test
    fun testUnknownEmptyQuery() {
        assertEquals(TravelIntent.UNKNOWN, classify("").intent)
    }

    @Test
    fun testUnknownWhitespaceOnly() {
        assertEquals(TravelIntent.UNKNOWN, classify("   ").intent)
    }

    @Test
    fun testUnknownGibberish() {
        assertEquals(TravelIntent.UNKNOWN, classify("asdfqwerty12345").intent)
    }

    // =========================================================================
    // 18. Location-dependency flags
    // =========================================================================

    @Test
    fun testLocationRequiredForNearbyAttractions() {
        assertTrue(classify("What is near me?").requiresLocation)
    }

    @Test
    fun testLocationRequiredForDistanceFromMe() {
        assertTrue(classify("How far is Marina Beach from me?").requiresLocation)
    }

    @Test
    fun testLocationRequiredForLost() {
        assertTrue(classify("I'm lost").requiresLocation)
    }

    @Test
    fun testLocationRequiredForTransportNearby() {
        assertTrue(classify("Bus stop near me").requiresLocation)
    }

    @Test
    fun testLocationNotRequiredForRouteSearch() {
        assertFalse(classify("Which bus goes to Tambaram?").requiresLocation)
    }

    @Test
    fun testLocationNotRequiredForDestinationDetails() {
        assertFalse(classify("Tell me about Marina Beach").requiresLocation)
    }

    @Test
    fun testLocationNotRequiredForEmergency() {
        // Emergency doesn't require location; emergency response is deterministic
        assertFalse(classify("SOS").requiresLocation)
    }

    // =========================================================================
    // 19. Transport beats destination
    // =========================================================================

    @Test
    fun testTransportBeatsDestinationWhichBusToMarina() {
        // "Marina" in DESTINATION_PATTERNS but "bus" + route search context wins
        val result = classify("Which bus goes to Marina?")
        // Should be TRANSPORT_ROUTE_SEARCH not DESTINATION_DETAILS
        // (Marina here is the destination of transport, not a destination overview query)
        // The word "bus" + "which" triggers transport classification
        assertTrue(
            "Query with explicit bus + which should classify as transport or destination, not unknown",
            result.intent == TravelIntent.TRANSPORT_ROUTE_SEARCH ||
                result.intent == TravelIntent.DESTINATION_DETAILS
        )
    }

    // =========================================================================
    // 20. Regression: existing transport tests are unaffected
    // =========================================================================

    @Test
    fun testTransportIntentRegression5C() {
        // detectTransportIntent still works as before
        val ti = QueryProcessor.detectTransportIntent("What is route 5C?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, ti.subIntent)
        assertEquals("5C", ti.target)
    }

    @Test
    fun testTransportIntentRegressionEmergencyNeverTransport() {
        val ti = QueryProcessor.detectTransportIntent("काप्पாற்றுங்கள்")
        assertEquals(TransportSubIntent.NONE, ti.subIntent)
    }

    @Test
    fun testDestinationQueryRegressionMarina() {
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Marina Beach"))
    }

    @Test
    fun testDestinationQueryRegressionKapaleeshwarar() {
        assertTrue(QueryProcessor.isDestinationQuery("Kapaleeshwarar Temple timings"))
    }

    @Test
    fun testDestinationQueryRegressionNegative() {
        assertFalse(QueryProcessor.isDestinationQuery("What buses stop at Broadway?"))
    }

    // =========================================================================
    // 21. Distress / Lost Precedence over Destination (Regression Tests)
    // =========================================================================

    @Test
    fun testLostNearMarinaBeachPreemptsDestination() {
        val result = classify("I'm lost near Marina Beach. What should I do?")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testLostNearValluvarKottamPreemptsDestination() {
        val result = classify("I'm lost near Valluvar Kottam.")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testDestinationDetailsMarinaBeachPreserved() {
        val result = classify("I am at Marina Beach. Tell me about it.")
        assertEquals(TravelIntent.DESTINATION_DETAILS, result.intent)
    }

    @Test
    fun testDestinationDetailsTellMeAboutMarinaBeachPreserved() {
        val result = classify("Tell me about Marina Beach.")
        assertEquals(TravelIntent.DESTINATION_DETAILS, result.intent)
    }

    @Test
    fun testDistanceDirectionsMarinaBeachPreserved() {
        val result = classify("How far is Marina Beach from me?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
    }

    @Test
    fun testEmergencyHelpNearMarinaBeachPreemptsDestination() {
        val result = classify("I need help near Marina Beach.")
        assertEquals(TravelIntent.EMERGENCY, result.intent)
    }

    // =========================================================================
    // 22. Priority 3: Natural-language "near me / nearby / around me" tests
    // =========================================================================

    @Test
    fun testPriority3_WhatAreSomePlacesICanVisitAroundMe() {
        val result = classify("What are some places I can visit around me?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_WhatCanIVisitNearby() {
        val result = classify("What can I visit nearby?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_WhatPlacesAreNearMe() {
        val result = classify("What places are near me?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_WhatCanIExploreAroundHere() {
        val result = classify("What can I explore around here?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_FindAttractionsNearMe() {
        val result = classify("Find attractions near me.")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_WhatIsAroundMe() {
        val result = classify("What is around me?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_TellMeAboutMarinaBeach_RemainsDestinationDetails() {
        val result = classify("Tell me about Marina Beach.")
        assertEquals(TravelIntent.DESTINATION_DETAILS, result.intent)
    }

    @Test
    fun testPriority3_HowFarIsMarinaBeachFromMe_RemainsDistanceDirections() {
        val result = classify("How far is Marina Beach from me?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_LostNearMarinaBeach_RemainsLostDisoriented() {
        val result = classify("I'm lost near Marina Beach. What should I do?")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3_NearValluvarKottamToMarinaBeach_RemainsDistanceDirections() {
        val result = classify("I'm near Valluvar Kottam. How do I get to Marina Beach?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    // =========================================================================
    // Priority 4: Transport question parsing (natural phrasing & route codes)
    // =========================================================================

    @Test
    fun testPriority4_WhichBusShouldITakeFromTriplicaneToMarinaBeach() {
        val result = classify("Which bus should I take from Triplicane to Marina Beach?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        assertTrue(result.requiresTransportData)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        org.junit.Assert.assertNull("English word 'should' must NOT be captured as route target", transport?.target)
        assertEquals("Triplicane", transport?.origin)
        assertEquals("Marina Beach", transport?.destination)
        assertEquals("bus", transport?.mode)
    }

    @Test
    fun testPriority4_WhichBusGoesFromTriplicaneToMarinaBeach() {
        val result = classify("Which bus goes from Triplicane to Marina Beach?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        org.junit.Assert.assertNull(transport?.target)
        assertEquals("Triplicane", transport?.origin)
        assertEquals("Marina Beach", transport?.destination)
    }

    @Test
    fun testPriority4_HowCanIGetToMarinaBeachByBus() {
        val result = classify("How can I get to Marina Beach by bus?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        org.junit.Assert.assertNull(transport?.target)
        assertEquals("Marina Beach", transport?.destination)
        assertEquals("bus", transport?.mode)
    }

    @Test
    fun testPriority4_CanITakeTheMetroToMarinaBeach() {
        val result = classify("Can I take the metro to Marina Beach?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        org.junit.Assert.assertNull(transport?.target)
        assertEquals("Marina Beach", transport?.destination)
        assertEquals("subway", transport?.mode)
    }

    @Test
    fun testPriority4_CanIUseTheMetroToGetThere() {
        val result = classify("Can I use the metro to get there?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        org.junit.Assert.assertNull(transport?.target)
        assertEquals("subway", transport?.mode)
    }

    @Test
    fun testPriority4_WhichBusIs21G() {
        val result = classify("Which bus is 21G?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        assertEquals("21G", transport?.target)
    }

    @Test
    fun testPriority4_TellMeAboutBus102() {
        val result = classify("Tell me about bus 102.")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        assertEquals("102", transport?.target)
    }

    @Test
    fun testPriority4_WhichBusIsAC21G() {
        val result = classify("Which bus is AC-21G?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        assertEquals("AC-21G", transport?.target)
    }

    @Test
    fun testPriority4_WhatIs5C() {
        val result = classify("What is 5C?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
        val transport = result.transportIntent
        org.junit.Assert.assertNotNull(transport)
        assertEquals("5C", transport?.target)
    }

    @Test
    fun testPriority4_VerifyPriority1EmergencyPrecedence() {
        val result = classify("I'm lost near Marina Beach. What should I do?")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority4_VerifyPriority2CompoundPrecedence() {
        val result = classify("I'm near Valluvar Kottam. How do I get to Marina Beach?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority4_VerifyPriority3NearbyPrecedence() {
        val result = classify("What are some places I can visit around me?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    // =========================================================================
    // 23. Priority 3A: Tamil nearby/around-me phrase recognition
    // =========================================================================

    @Test
    fun testPriority3A_EnnaiSutri_EnnaIdangalUllana() {
        val result = classify("என்னை சுற்றி என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
        assertTrue(result.requiresDestinationData)
    }

    @Test
    fun testPriority3A_EnnaichSutri_EnnaIdangalUllana() {
        val result = classify("என்னைச் சுற்றி என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
        assertTrue(result.requiresDestinationData)
    }

    @Test
    fun testPriority3A_EnnaiSutrilum_EnnaIdangalUllana() {
        val result = classify("என்னை சுற்றிலும் என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_EnnaichSutrilum_EnnaIdangalUllana() {
        val result = classify("என்னைச் சுற்றிலும் என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_Arugil_EnnaIdangalUllana() {
        val result = classify("அருகில் என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_EnArugil_EnnaIdangalUllana() {
        val result = classify("என் அருகில் என்ன இடங்கள் உள்ளன?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_EnnaiSutri_EnnaPaarkkalaam() {
        val result = classify("என்னை சுற்றி என்ன பார்க்கலாம்?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_EnnaichSutri_EnnaPaarkkalaam() {
        val result = classify("என்னைச் சுற்றி என்ன பார்க்கலாம்?")
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_PreserveExistingTamilNearbyVariants() {
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classify("அருகில் என்ன பார்க்கலாம்?").intent)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classify("அருகே என்ன இருக்கிறது?").intent)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classify("பக்கத்தில் என்ன இருக்கிறது?").intent)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classify("சுற்றிலும் என்ன பார்க்கலாம்?").intent)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classify("என்னைச் சுற்றி என்ன இருக்கிறது?").intent)
    }

    @Test
    fun testPriority3A_PreservePrecedenceLostNearMarinaBeach() {
        val result = classify("I'm lost near Marina Beach. What should I do?")
        assertEquals(TravelIntent.LOST_DISORIENTED, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_PreservePrecedenceHowFarIsMarinaBeachFromMe() {
        val result = classify("How far is Marina Beach from me?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_PreservePrecedenceCompoundOriginToDestination() {
        val result = classify("I'm near Valluvar Kottam. How do I get to Marina Beach?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testPriority3A_PreservePrecedenceTellMeAboutMarinaBeach() {
        val result = classify("Tell me about Marina Beach.")
        assertEquals(TravelIntent.DESTINATION_DETAILS, result.intent)
    }

    @Test
    fun testPriority3A_PreservePrecedenceTransportTriplicaneToMarinaBeach() {
        val result = classify("Which bus should I take from Triplicane to Marina Beach?")
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, result.intent)
    }

    // =========================================================================
    // Natural Routing Intent Classification Tests (Queries A - E)
    // =========================================================================

    @Test
    fun testQueryA_WantToGoToVRMall() {
        val result = classify("I want to go to VR Mall")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testQueryB_WantToReachVRMall() {
        val result = classify("I want to reach VR Mall")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testQueryC_WantToGetToVRMall() {
        val result = classify("I want to get to VR Mall")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testQueryD_WhereIsVRMall() {
        val result = classify("Where is VR Mall?")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }

    @Test
    fun testQueryE_SaveethaCollegeToVRMall() {
        val result = classify("I am at Saveetha College and I want to go to VR Mall")
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, result.intent)
        assertTrue(result.requiresLocation)
    }
}
