package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextualDestinationMemoryTest {

    @Before
    fun setUp() {
        QueryProcessor.clearSessionDestination()
    }

    // TEST 1 — Remember destination
    @Test
    fun test1_rememberDestination() {
        val q = "Tell me about Marina Beach"
        val resolved = QueryProcessor.resolveDestination(q)
        assertNotNull("Marina Beach must be resolved", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)
        assertEquals("Marina Beach", QueryProcessor.lastResolvedDestination?.canonicalName)
    }

    // TEST 2 — "there"
    @Test
    fun test2_resolvesThereToRememberedDestination() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        val followUp = "What can I do there?"
        val resolved = QueryProcessor.resolveDestination(followUp)
        assertNotNull("Should resolve anaphora 'there' to remembered destination", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)

        val classification = QueryProcessor.classifyTravelIntent(followUp)
        assertEquals(TravelIntent.ACTIVITY_PLANNING, classification.intent)
        assertTrue(classification.requiresDestinationData)

        val expanded = QueryProcessor.resolveAnaphora(followUp)
        assertTrue(expanded.contains("Marina Beach"))
    }

    // TEST 3 — "it"
    @Test
    fun test3_resolvesItToRememberedDestinationDistanceDirections() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        val followUp = "How far is it from me?"
        val resolved = QueryProcessor.resolveDestination(followUp)
        assertNotNull("Should resolve pronoun 'it' to remembered destination", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)

        val classification = QueryProcessor.classifyTravelIntent(followUp)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
        assertTrue(classification.requiresDestinationData)

        val expanded = QueryProcessor.resolveAnaphora(followUp)
        assertTrue(expanded.contains("Marina Beach"))
    }

    // TEST 4 — "that place"
    @Test
    fun test4_resolvesThatPlaceToRememberedDestination() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        val followUp = "Tell me about that place"
        val resolved = QueryProcessor.resolveDestination(followUp)
        assertNotNull("Should resolve 'that place' to remembered destination", resolved)
        assertEquals("Marina Beach", resolved?.canonicalName)

        val classification = QueryProcessor.classifyTravelIntent(followUp)
        assertEquals(TravelIntent.DESTINATION_DETAILS, classification.intent)

        val expanded = QueryProcessor.resolveAnaphora(followUp)
        assertEquals("Tell me about Marina Beach", expanded)
    }

    // TEST 5 — no memory
    @Test
    fun test5_noMemoryDoesNotInventDestination() {
        QueryProcessor.clearSessionDestination()
        assertNull(QueryProcessor.lastResolvedDestination)

        val query = "What can I do there?"
        val resolved = QueryProcessor.resolveDestination(query)
        assertNull("Must NOT invent a destination when no previous destination is remembered", resolved)

        val expanded = QueryProcessor.resolveAnaphora(query)
        assertEquals("Query should remain unchanged when no context exists", query, expanded)
    }

    // TEST 6 — memory replacement
    @Test
    fun test6_memoryReplacement() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")
        assertEquals("Marina Beach", QueryProcessor.lastResolvedDestination?.canonicalName)

        QueryProcessor.resolveDestination("Tell me about Valluvar Kottam")
        assertEquals("Valluvar Kottam", QueryProcessor.lastResolvedDestination?.canonicalName)

        val followUp = "How far is it from me?"
        val resolved = QueryProcessor.resolveDestination(followUp)
        assertNotNull(resolved)
        assertEquals("Must resolve to Valluvar Kottam, NOT Marina Beach", "Valluvar Kottam", resolved?.canonicalName)

        val expanded = QueryProcessor.resolveAnaphora(followUp)
        assertEquals("How far is Valluvar Kottam from me?", expanded)
    }

    // TEST 7 — explicit destination overrides memory
    @Test
    fun test7_explicitDestinationOverridesMemory() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")
        assertEquals("Marina Beach", QueryProcessor.lastResolvedDestination?.canonicalName)

        val query = "Tell me about Valluvar Kottam"
        val resolved = QueryProcessor.resolveDestination(query)
        assertNotNull(resolved)
        assertEquals("Valluvar Kottam", resolved?.canonicalName)
        assertEquals("Valluvar Kottam must replace Marina Beach as the new remembered destination",
            "Valluvar Kottam", QueryProcessor.lastResolvedDestination?.canonicalName)
    }

    // TEST 8 — second follow-up
    @Test
    fun test8_secondFollowUpResolvesThereToValluvarKottam() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")
        QueryProcessor.resolveDestination("Tell me about Valluvar Kottam")

        val query = "What can I do there?"
        val resolved = QueryProcessor.resolveDestination(query)
        assertNotNull(resolved)
        assertEquals("Valluvar Kottam", resolved?.canonicalName)

        val expanded = QueryProcessor.resolveAnaphora(query)
        assertTrue("Expanded query must contain Valluvar Kottam", expanded.contains("Valluvar Kottam"))
    }

    @Test
    fun testAdditionalReferences_thisPlaceThatDestination() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        assertEquals("Is Marina Beach safe?", QueryProcessor.resolveAnaphora("Is this place safe?"))
        assertEquals("How to reach Marina Beach?", QueryProcessor.resolveAnaphora("How to reach that destination?"))
        assertEquals("Tell me about Marina Beach", QueryProcessor.resolveAnaphora("Tell me about this destination"))
    }

    @Test
    fun testUnknownDestinationDoesNotCorruptOrUseRememberedDestination() {
        QueryProcessor.resolveDestination("Tell me about Marina Beach")

        // "How do I get to VR Mall?" has an explicit (unverified) candidate, not an anaphora
        val query = "How do I get to VR Mall?"
        val resolved = QueryProcessor.resolveDestination(query)
        assertNull("VR Mall is unverified; must not resolve", resolved)
        assertEquals("Marina Beach must remain the remembered destination",
            "Marina Beach", QueryProcessor.lastResolvedDestination?.canonicalName)
    }
}
