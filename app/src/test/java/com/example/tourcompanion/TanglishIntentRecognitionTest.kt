package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TanglishIntentRecognitionTest {

    @Before
    fun setUp() {
        QueryProcessor.clearSessionDestination()
    }

    // ==========================================================
    // A. DIRECTIONS
    // ==========================================================

    @Test
    fun test01_marinaBeachKuEpdiPoganum() {
        val q = "Marina beach ku epdi poganum?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
        assertTrue(classification.requiresDestinationData)

        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Destination should be resolved", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)
    }

    @Test
    fun test02_marinaBeachKuEppadiPoganum() {
        val q = "Marina beach ku eppadi poganum?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)

        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Destination should be resolved", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)
    }

    @Test
    fun test03_marinaBeachKuEpdiPoga() {
        val q = "Marina beach ku epdi poga?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)

        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Destination should be resolved", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)
    }

    @Test
    fun test04_valluvarKottamKuEpdiPogalam() {
        val q = "Valluvar Kottam ku epdi pogalam?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)

        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Destination should be resolved", resolved)
        assertEquals("Valluvar Kottam", resolved?.canonicalName)
    }

    @Test
    fun test05_howDoIGetToMarinaBeach_EnglishPreserved() {
        val q = "How do I get to Marina Beach?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)

        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Destination should be resolved", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)
    }

    // ==========================================================
    // B. NEARBY
    // ==========================================================

    @Test
    fun test06_enakkuPakkathulaEnnaPlacesIrukku() {
        val q = "Enakku pakkathula enna places irukku?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
        assertTrue(classification.requiresDestinationData)
    }

    @Test
    fun test07_enakkuPakathulaEnnaPlacesIrukku() {
        val q = "Enakku pakathula enna places irukku?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun test08_enakkuArugulaEnnaIrukku() {
        val q = "Enakku arugula enna irukku?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun test09_ingaPakkathulaEnnaPlacesIrukku() {
        val q = "Inga pakkathula enna places irukku?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun test10_whatPlacesAreNearMe_EnglishPreserved() {
        val q = "What places are near me?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    // ==========================================================
    // C. REGRESSION & PRIORITY
    // ==========================================================

    @Test
    fun test11_tellMeAboutMarinaBeach_DestinationDetails() {
        val q = "Tell me about Marina Beach"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DESTINATION_DETAILS, classification.intent)

        val resolved = QueryProcessor.resolveDestination(q)
        assertEquals("Marina Beach", resolved?.canonicalName)
    }

    @Test
    fun test12_howFarIsMarinaBeachFromMe_DistanceDirections() {
        val q = "How far is Marina Beach from me?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
    }

    @Test
    fun test13_isMarinaBeachSafeAtNight_Safety() {
        val q = "Is Marina Beach safe at night?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.SAFETY, classification.intent)
    }

    @Test
    fun test14_whichBusShouldITakeToMarinaBeach_Transport() {
        val q = "Which bus should I take to Marina Beach?"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, classification.intent)
    }

    @Test
    fun test15_emergency_EmergencyPriority() {
        val q = "Emergency"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.EMERGENCY, classification.intent)
    }

    @Test
    fun test16_helpIAmLost_LostDisoriented() {
        val q = "Help, I am lost"
        val classification = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.LOST_DISORIENTED, classification.intent)
    }

    @Test
    fun test17_whatCanIDoThere_ConversationMemory() {
        // Step 1: user asks about Marina Beach
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        // Step 2: user asks follow-up with "there"
        val followUp = "What can I do there?"
        val resolved = QueryProcessor.resolveDestination(followUp)
        assertNotNull("Should resolve 'there' to Marina Beach", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)

        val classification = QueryProcessor.classifyTravelIntent(followUp)
        assertEquals(TravelIntent.ACTIVITY_PLANNING, classification.intent)
    }
}
