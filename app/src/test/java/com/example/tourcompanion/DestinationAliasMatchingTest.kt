package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused unit tests for Destination Alias Matching.
 *
 * Verifies:
 * - English alias matching (Marina, Elliot's Beach, Mylapore Temple, Fort Museum Chennai, Birla Planetarium)
 * - Tamil alias matching (மெரினா கடற்கரை, மயிலாப்பூர் கோயில், வள்ளுவர் கோட்டம், கிண்டி தேசிய பூங்கா)
 * - Hindi alias matching (मरीना बीच, कपालीश्वर मंदिर, वल्लुवर कोट्टम, बिरला तारामंडल)
 * - Negative cases (unknown destination, empty query, unrelated travel question)
 * - Canonical destination names matching
 * - Ambiguity protection
 * - Specificity ranking (exact match > longest alias match)
 * - Live backend fetching via DestinationApiClient (destination-aliases.php)
 */
class DestinationAliasMatchingTest {

    private val testBaseUrl = "http://localhost/tourcompanion/api/"
    private val apiClient = DestinationApiClient(testBaseUrl)

    private lateinit var matcher: DestinationAliasMatcher

    @Before
    fun setUp() {
        // Fetch verified aliases directly from database via API (source of truth)
        val loadedAliases = runBlocking {
            val response = apiClient.getDestinationAliases()
            if (response is DestinationApiClient.Response.Success) {
                response.data
            } else {
                emptyList()
            }
        }

        val canonicalMap = mapOf(
            1 to "Marina Beach",
            2 to "Arulmigu Kapaleeshwarar Temple",
            3 to "Fort St. George & St. Mary’s Church",
            4 to "Government Museum & National Art Gallery, Egmore",
            5 to "Besant Nagar Beach (Elliot's Beach)",
            6 to "Santhome Cathedral Basilica",
            7 to "Parthasarathy Temple, Triplicane",
            8 to "Valluvar Kottam",
            9 to "Guindy National Park",
            10 to "B.M. Birla Planetarium, Kotturpuram",
            11 to "Vivekananda House",
            12 to "Chennai Lighthouse",
            13 to "M.G.R. and Amma Memorial",
            14 to "Anna Memorial",
            15 to "Victory War Memorial",
            16 to "Gandhi Mandapam",
            17 to "Armenian Church",
            18 to "Thousand Lights Mosque",
            19 to "Ripon Building",
            20 to "Our Lady of Light Church (Luz Church)",
            24 to "DakshinaChitra Heritage Museum",
            31 to "Semmozhi Poonga",
            36 to "Arignar Anna Zoological Park (Vandalur Zoo)",
            50 to "Royapuram Railway Station"
        )

        matcher = DestinationAliasMatcher.fromAliases(loadedAliases, canonicalMap)
        QueryProcessor.aliasMatcher = matcher
    }

    // =========================================================================
    // 1. English Alias Matching Tests
    // =========================================================================

    @Test
    fun testEnglishAlias_Marina() {
        val result = matcher.resolveDestination("Marina")
        assertNotNull("Marina alias must match", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
    }

    @Test
    fun testEnglishAlias_ElliotsBeach() {
        val result = matcher.resolveDestination("Elliot's Beach")
        assertNotNull("Elliot's Beach alias must match", result)
        assertEquals(5, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Besant Nagar"))
    }

    @Test
    fun testEnglishAlias_MylaporeTemple() {
        val result = matcher.resolveDestination("Mylapore Temple")
        assertNotNull("Mylapore Temple alias must match", result)
        assertEquals(2, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Kapaleeshwarar"))
    }

    @Test
    fun testEnglishAlias_FortMuseumChennai() {
        val result = matcher.resolveDestination("Fort Museum Chennai")
        assertNotNull("Fort Museum Chennai alias must match", result)
        assertEquals(3, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Fort St. George"))
    }

    @Test
    fun testEnglishAlias_BirlaPlanetarium() {
        val result = matcher.resolveDestination("Birla Planetarium")
        assertNotNull("Birla Planetarium alias must match", result)
        assertEquals(10, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Birla Planetarium"))
    }

    // =========================================================================
    // 2. Tamil Alias Matching Tests
    // =========================================================================

    @Test
    fun testTamilAlias_MarinaBeach() {
        val result = matcher.resolveDestination("மெரினா கடற்கரை")
        assertNotNull("Tamil alias மெரினா கடற்கரை must match", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
        assertEquals("ta", result.language)
    }

    @Test
    fun testTamilAlias_MylaporeTemple() {
        val result = matcher.resolveDestination("மயிலாப்பூர் கோயில்")
        assertNotNull("Tamil alias மயிலாப்பூர் கோயில் must match", result)
        assertEquals(2, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Kapaleeshwarar"))
        assertEquals("ta", result.language)
    }

    @Test
    fun testTamilAlias_ValluvarKottam() {
        val result = matcher.resolveDestination("வள்ளுவர் கோட்டம்")
        assertNotNull("Tamil alias வள்ளுவர் கோட்டம் must match", result)
        assertEquals(8, result!!.destinationId)
        assertEquals("Valluvar Kottam", result.canonicalName)
        assertEquals("ta", result.language)
    }

    @Test
    fun testTamilAlias_GuindyNationalPark() {
        val result = matcher.resolveDestination("கிண்டி தேசிய பூங்கா")
        assertNotNull("Tamil alias கிண்டி தேசிய பூங்கா must match", result)
        assertEquals(9, result!!.destinationId)
        assertEquals("Guindy National Park", result.canonicalName)
        assertEquals("ta", result.language)
    }

    // =========================================================================
    // 3. Hindi Alias Matching Tests
    // =========================================================================

    @Test
    fun testHindiAlias_MarinaBeach() {
        val result = matcher.resolveDestination("मरीना बीच")
        assertNotNull("Hindi alias मरीना बीच must match", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
        assertEquals("hi", result.language)
    }

    @Test
    fun testHindiAlias_KapaleeshwararTemple() {
        val result = matcher.resolveDestination("कपालीश्वर मंदिर")
        assertNotNull("Hindi alias कपालीश्वर मंदिर must match", result)
        assertEquals(2, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Kapaleeshwarar"))
        assertEquals("hi", result.language)
    }

    @Test
    fun testHindiAlias_ValluvarKottam() {
        val result = matcher.resolveDestination("वल्लुवर कोट्टम")
        assertNotNull("Hindi alias वल्लुवर कोट्टम must match", result)
        assertEquals(8, result!!.destinationId)
        assertEquals("Valluvar Kottam", result.canonicalName)
        assertEquals("hi", result.language)
    }

    @Test
    fun testHindiAlias_BirlaPlanetarium() {
        val result = matcher.resolveDestination("बिरला तारामंडल")
        assertNotNull("Hindi alias बिरला तारामंडल must match", result)
        assertEquals(10, result!!.destinationId)
        assertTrue(result.canonicalName.contains("Birla Planetarium"))
        assertEquals("hi", result.language)
    }

    // =========================================================================
    // 4. Negative Matching Tests
    // =========================================================================

    @Test
    fun testNegative_CompletelyUnknownDestination() {
        val result = matcher.resolveDestination("completely unknown destination")
        assertNull("Unknown destination must return null", result)
    }

    @Test
    fun testNegative_EmptyOrBlankQuery() {
        assertNull("Empty query must return null", matcher.resolveDestination(""))
        assertNull("Blank query must return null", matcher.resolveDestination("   "))
        assertNull("Null query must return null", matcher.resolveDestination(null))
    }

    @Test
    fun testNegative_UnrelatedTravelQuestion() {
        val result = matcher.resolveDestination("What should I pack for rain?")
        assertNull("Unrelated query must return null", result)
    }

    // =========================================================================
    // 5. Canonical Destination Names Tests
    // =========================================================================

    @Test
    fun testCanonicalDestination_MarinaBeach() {
        val result = matcher.resolveDestination("Marina Beach")
        assertNotNull(result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
    }

    @Test
    fun testCanonicalDestination_KapaleeshwararTemple() {
        val result = matcher.resolveDestination("Arulmigu Kapaleeshwarar Temple")
        assertNotNull(result)
        assertEquals(2, result!!.destinationId)
    }

    @Test
    fun testCanonicalDestination_ValluvarKottam() {
        val result = matcher.resolveDestination("Valluvar Kottam")
        assertNotNull(result)
        assertEquals(8, result!!.destinationId)
    }

    @Test
    fun testCanonicalDestination_GuindyNationalPark() {
        val result = matcher.resolveDestination("Guindy National Park")
        assertNotNull(result)
        assertEquals(9, result!!.destinationId)
    }

    // =========================================================================
    // 6. Ambiguity Protection & Specificity Ranking Tests
    // =========================================================================

    @Test
    fun testSpecificity_PrefersLongestAliasMatch() {
        // Query contains both "Marina" (length 6) and "Marina Beach" (length 12)
        val result = matcher.resolveDestination("I want to visit Marina Beach today")
        assertNotNull(result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.matchedAlias)
    }

    @Test
    fun testAmbiguityProtection_EqualSpecificityDifferentDestinationsReturnsNull() {
        // Construct synthetic ambiguous matcher where "Central Park" points to two distinct destination IDs
        val ambiguousAliases = listOf(
            DestinationAlias(1, 101, "Park A", "Central Park", "en"),
            DestinationAlias(2, 102, "Park B", "Central Park", "en")
        )
        val ambiguousMatcher = DestinationAliasMatcher(ambiguousAliases)
        val result = ambiguousMatcher.resolveDestination("Central Park")
        assertNull("Ambiguous tie between two different destinations must return null", result)
    }

    // =========================================================================
    // 7. Live Backend & QueryProcessor Integration Tests
    // =========================================================================

    @Test
    fun testLiveBackendDestinationAliasesEndpoint() = runBlocking {
        val response = apiClient.getDestinationAliases()
        if (response is DestinationApiClient.Response.Success) {
            assertTrue("Aliases count should be at least 113, was ${response.count}", response.count >= 113)
            assertTrue("Aliases data size should be at least 113, was ${response.data.size}", response.data.size >= 113)
            assertTrue(response.data.any { it.alias == "Mylapore Temple" && it.destinationId == 2 })
            assertTrue(response.data.any { it.alias == "Bessie" && it.destinationId == 5 })
            assertTrue(response.data.any { it.alias == "வள்ளுவர் கோட்டம்" && it.destinationId == 8 })
            assertTrue(response.data.any { it.alias == "मरीना बीच" && it.destinationId == 1 })
            assertTrue(response.data.any { it.alias == "Dakshina Chitra" })
            assertTrue(response.data.any { it.alias == "Semmozhi Poonga" })
            assertTrue(response.data.any { it.alias == "Vandalur Zoo" })
        } else {
            assertTrue("Should be Failure when server is offline", response is DestinationApiClient.Response.Failure)
        }
    }

    @Test
    fun testQueryProcessorIntegration_RecognizesAliases() {
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Mylapore Temple"))
        assertTrue(QueryProcessor.isDestinationQuery("வள்ளுவர் கோட்டம் எங்கே உள்ளது?"))
        assertTrue(QueryProcessor.isDestinationQuery("मरीना बीच कैसे जाएँ?"))
        assertFalse(QueryProcessor.isDestinationQuery("What should I pack for rain?"))
    }

    @Test
    fun testQueryProcessor_SyncAliases_InitializesAliasMatcher() = runBlocking {
        val synced = QueryProcessor.syncAliases(apiClient)
        // If live backend is reachable, verify matcher initialized with all minimum destinations
        if (synced) {
            assertNotNull("aliasMatcher must be non-null after successful sync", QueryProcessor.aliasMatcher)
            assertTrue(QueryProcessor.isDestinationQuery("Marina Beach"))
            assertTrue(QueryProcessor.isDestinationQuery("Kapaleeshwarar Temple"))
            assertTrue(QueryProcessor.isDestinationQuery("Valluvar Kottam"))
            assertTrue(QueryProcessor.isDestinationQuery("Guindy National Park"))
            assertTrue(QueryProcessor.isDestinationQuery("Dakshina Chitra"))
            assertTrue(QueryProcessor.isDestinationQuery("Semmozhi Poonga"))
            assertTrue(QueryProcessor.isDestinationQuery("Vandalur Zoo"))
            assertTrue(QueryProcessor.isDestinationQuery("Royapuram Railway Station"))
            assertTrue(QueryProcessor.isDestinationQuery("Where is Dakshina Chitra?"))
        } else {
            // When offline or backend unreachable, verify fallback matcher can be initialized manually
            val syntheticAliases = listOf(
                DestinationAlias(1, 1, "Marina Beach", "Marina Beach", "en"),
                DestinationAlias(2, 2, "Kapaleeshwarar Temple", "Kapaleeshwarar Temple", "en"),
                DestinationAlias(3, 8, "Valluvar Kottam", "Valluvar Kottam", "en"),
                DestinationAlias(4, 9, "Guindy National Park", "Guindy National Park", "en"),
                DestinationAlias(5, 24, "DakshinaChitra Heritage Museum", "Dakshina Chitra", "en"),
                DestinationAlias(6, 31, "Semmozhi Poonga", "Semmozhi Poonga", "en"),
                DestinationAlias(7, 36, "Arignar Anna Zoological Park", "Vandalur Zoo", "en"),
                DestinationAlias(8, 50, "Royapuram Railway Station", "Royapuram Railway Station", "en")
            )
            QueryProcessor.aliasMatcher = DestinationAliasMatcher.fromAliases(syntheticAliases)
            assertNotNull(QueryProcessor.aliasMatcher)
            assertTrue(QueryProcessor.isDestinationQuery("Marina Beach"))
            assertTrue(QueryProcessor.isDestinationQuery("Kapaleeshwarar Temple"))
            assertTrue(QueryProcessor.isDestinationQuery("Valluvar Kottam"))
            assertTrue(QueryProcessor.isDestinationQuery("Guindy National Park"))
            assertTrue(QueryProcessor.isDestinationQuery("Dakshina Chitra"))
            assertTrue(QueryProcessor.isDestinationQuery("Semmozhi Poonga"))
            assertTrue(QueryProcessor.isDestinationQuery("Vandalur Zoo"))
            assertTrue(QueryProcessor.isDestinationQuery("Royapuram Railway Station"))
            assertTrue(QueryProcessor.isDestinationQuery("Where is Dakshina Chitra?"))
        }
    }

    @Test
    fun testQueryProcessor_FallbackWhenAliasMatcherIsNull() {
        QueryProcessor.aliasMatcher = null
        assertNull(QueryProcessor.aliasMatcher)

        // Existing 5 hardcoded patterns must still work
        assertTrue(QueryProcessor.isDestinationQuery("Marina Beach"))
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Kapaleeshwarar"))
        assertTrue(QueryProcessor.isDestinationQuery("Where is Fort St George?"))
        assertTrue(QueryProcessor.isDestinationQuery("Government Museum timings"))
        assertTrue(QueryProcessor.isDestinationQuery("Besant Nagar Beach"))

        // Unrelated query must return false
        assertFalse(QueryProcessor.isDestinationQuery("What is the weather today?"))
    }

    @Test
    fun testQueryProcessor_InitAliasesAsync_DoesNotCrash() {
        // Must complete without throwing any exception on calling thread
        QueryProcessor.initAliasesAsync(apiClient)
        // Ensure QueryProcessor can be queried safely immediately after
        assertTrue(QueryProcessor.isDestinationQuery("Marina Beach"))
    }

    // =========================================================================
    // 7. Compound Origin -> Destination Resolution Tests (Priority 2)
    // =========================================================================

    @Test
    fun testCompound_MarinaBeachToSemmozhiPoonga_SelectsSemmozhiPoonga() {
        // "I'm at Marina Beach. How far is Semmozhi Poonga?" -> target: Semmozhi Poonga
        val result = matcher.resolveDestination("I'm at Marina Beach. How far is Semmozhi Poonga?")
        assertNotNull("Must resolve destination", result)
        assertEquals(31, result!!.destinationId)
        assertTrue("Target must be Semmozhi Poonga", result.canonicalName.contains("Semmozhi Poonga"))
    }

    @Test
    fun testCompound_FromMarinaBeachToKapaleeshwararTemple_SelectsKapaleeshwarar() {
        // "From Marina Beach to Kapaleeshwarar Temple" -> target: Kapaleeshwarar Temple
        val result = matcher.resolveDestination("From Marina Beach to Kapaleeshwarar Temple")
        assertNotNull("Must resolve destination", result)
        assertEquals(2, result!!.destinationId)
        assertTrue("Target must be Kapaleeshwarar Temple", result.canonicalName.contains("Kapaleeshwarar"))
    }

    @Test
    fun testCompound_NearValluvarKottamToMarinaBeach_SelectsMarinaBeach() {
        // "I am near Valluvar Kottam. How do I get to Marina Beach?" -> target: Marina Beach
        val result = matcher.resolveDestination("I am near Valluvar Kottam. How do I get to Marina Beach?")
        assertNotNull("Must resolve destination", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
    }

    @Test
    fun testSingleDestination_HowFarIsMarinaBeachFromMe_SelectsMarinaBeach() {
        // "How far is Marina Beach from me?" -> target: Marina Beach
        val result = matcher.resolveDestination("How far is Marina Beach from me?")
        assertNotNull("Must resolve destination", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
    }

    @Test
    fun testSingleDestination_TellMeAboutMarinaBeach_SelectsMarinaBeach() {
        // "Tell me about Marina Beach." -> target: Marina Beach
        val result = matcher.resolveDestination("Tell me about Marina Beach.")
        assertNotNull("Must resolve destination", result)
        assertEquals(1, result!!.destinationId)
        assertEquals("Marina Beach", result.canonicalName)
    }

    @Test
    fun testLostQuery_LostNearMarinaBeach_ClassifiesAsLostDisoriented() {
        // "I'm lost near Marina Beach. What should I do?" -> Priority 1 LOST_DISORIENTED takes precedence
        val classification = QueryProcessor.classifyTravelIntent("I'm lost near Marina Beach. What should I do?")
        assertEquals(TravelIntent.LOST_DISORIENTED, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    // =========================================================================
    // 8. Priority 5A: Honest Origin Handling Tests
    // =========================================================================

    @Test
    fun testPriority5A_Test1_ImAtMarinaBeachHowDoIGetToKapaleeshwararTemple() {
        val query = "I'm at Marina Beach. How do I get to Kapaleeshwarar Temple?"
        val compound = matcher.resolveCompoundDestinations(query)
        assertNotNull("Compound resolution must not be null", compound)
        assertNotNull("Origin must be extracted", compound!!.origin)
        assertNotNull("Target must be extracted", compound.target)
        assertEquals("Marina Beach", compound.origin!!.canonicalName)
        assertTrue("Target must be Kapaleeshwarar Temple", compound.target.canonicalName.contains("Kapaleeshwarar"))

        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)

        // Verify honest limitation message format
        val limitationMsg = AnswerRepository.namedOriginRoutingLimitationMessage(
            compound.target.canonicalName,
            compound.origin!!.canonicalName
        )
        assertTrue(limitationMsg.contains("can't calculate a route from Marina Beach"))
        assertTrue(limitationMsg.contains("phone's current location is used only for distance estimates"))
        assertFalse(limitationMsg.contains("5.3 km"))
    }

    @Test
    fun testPriority5A_Test2_HowDoIGetFromMarinaBeachToKapaleeshwararTemple() {
        val query = "How do I get from Marina Beach to Kapaleeshwarar Temple?"
        val compound = matcher.resolveCompoundDestinations(query)
        assertNotNull("Compound resolution must not be null", compound)
        assertEquals("Marina Beach", compound!!.origin?.canonicalName)
        assertTrue("Target must be Kapaleeshwarar Temple", compound.target.canonicalName.contains("Kapaleeshwarar"))

        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
    }

    @Test
    fun testPriority5A_Test3_FromMarinaBeachToKapaleeshwararTemple() {
        val query = "From Marina Beach to Kapaleeshwarar Temple"
        val compound = matcher.resolveCompoundDestinations(query)
        assertNotNull("Compound resolution must not be null", compound)
        assertEquals("Marina Beach", compound!!.origin?.canonicalName)
        assertTrue("Target must be Kapaleeshwarar Temple", compound.target.canonicalName.contains("Kapaleeshwarar"))

        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
    }

    @Test
    fun testPriority5A_Test4_HowFarIsMarinaBeachFromMe_PreservedGpsDistance() {
        val query = "How far is Marina Beach from me?"
        val compound = matcher.resolveCompoundDestinations(query)
        assertNotNull("Must resolve destination", compound)
        assertEquals("Marina Beach", compound!!.target.canonicalName)
        assertNull("Origin must be null for 'from me'", compound.origin)

        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.DISTANCE_DIRECTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun testPriority5A_Test5_TellMeAboutMarinaBeach_NormalDestinationInfo() {
        val query = "Tell me about Marina Beach."
        val compound = matcher.resolveCompoundDestinations(query)
        assertNotNull("Must resolve destination", compound)
        assertEquals("Marina Beach", compound!!.target.canonicalName)
        assertNull("Origin must be null", compound.origin)

        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.DESTINATION_DETAILS, classification.intent)
        assertFalse("Must not be distance intent", classification.requiresLocation)
    }

    @Test
    fun testPriority5A_Test6_ImLostNearMarinaBeach_PreservedLostDisoriented() {
        val query = "I'm lost near Marina Beach."
        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.LOST_DISORIENTED, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun testPriority5A_Test7_WhatCanIVisitAroundMe_PreservedNearbyAttractions() {
        val query = "What can I visit around me?"
        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.NEARBY_ATTRACTIONS, classification.intent)
        assertTrue(classification.requiresLocation)
    }

    @Test
    fun testPriority5A_Test8_WhichBusShouldITakeFromTriplicaneToMarinaBeach_PreservedTransportRouteSearch() {
        val query = "Which bus should I take from Triplicane to Marina Beach?"
        val classification = QueryProcessor.classifyTravelIntent(query)
        assertEquals(TravelIntent.TRANSPORT_ROUTE_SEARCH, classification.intent)
        assertNotNull(classification.transportIntent)
        assertEquals("Triplicane", classification.transportIntent?.origin)
        assertEquals("Marina Beach", classification.transportIntent?.destination)
        assertNull("Should word must not be captured as route code", classification.transportIntent?.target)
    }
}
