package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GenericTipHijackingRegressionTest {

    @Before
    fun setUp() {
        QueryProcessor.clearSessionDestination()
    }

    @Test
    fun test1_peacefulEveningInChennai_classifiedAsDestinationDiscovery() {
        val q = "Where can I go for a peaceful evening in Chennai?"
        val result = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.DESTINATION_DISCOVERY, result.intent)
        assertNull(QueryProcessor.resolveDestination(q))
    }

    @Test
    fun test2_visitABeachWhatCanIDo_classifiedAsActivityPlanningWithNoSingleBeach() {
        val q = "I want to visit a beach in Chennai. What can I do?"
        val result = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.ACTIVITY_PLANNING, result.intent)
        assertNull(QueryProcessor.resolveDestination(q))
    }

    @Test
    fun test3_whatCanIDoAtMarinaBeach_preservesMarinaBeachActivity() {
        val q = "What can I do at Marina Beach?"
        val result = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.ACTIVITY_PLANNING, result.intent)
        val dest = QueryProcessor.resolveDestination(q)
        assertNotNull(dest)
        assertEquals("Marina Beach", dest?.canonicalName)
    }

    @Test
    fun test4_whatCanIDoThereAfterMarinaBeach_preservesMemory() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")
        val followUp = "What can I do there?"
        val result = QueryProcessor.classifyTravelIntent(followUp)
        assertEquals(TravelIntent.ACTIVITY_PLANNING, result.intent)
        val dest = QueryProcessor.resolveDestination(followUp)
        assertNotNull(dest)
        assertEquals("Marina Beach", dest?.canonicalName)
    }

    @Test
    fun test5_unsafeTravellingAlone_preservesSafetyOrTipIntent() {
        val q = "I feel unsafe while travelling alone in Chennai. What should I do?"
        val result = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.TRAVEL_TIP, result.intent)
    }

    @Test
    fun test6_staySafeAtTheBeach_preservesSafetyOrTipIntent() {
        val q = "What should I do to stay safe at the beach?"
        val result = QueryProcessor.classifyTravelIntent(q)
        assertEquals(TravelIntent.TRAVEL_TIP, result.intent)
    }
}
