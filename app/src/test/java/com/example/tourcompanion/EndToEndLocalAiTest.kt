package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * End-to-End Local AI integration tests for Phase 3.
 * Validates the complete flow from Android client through ask.php,
 * local Gemma 3 4B synthesis, deterministic emergency, MTC transport,
 * multilingual handling (English, Tamil, Hindi), and offline fallback.
 */
class EndToEndLocalAiTest {

    // Test endpoint pointing to the local backend
    private val client = AiAnswerClient("http://127.0.0.1/tourcompanion/api/ask.php")

    @Test
    fun test01_DestinationQueryMarinaBeach() = runBlocking {
        val q = "Tell me about Marina Beach."
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertEquals("Marina Beach", res!!.topic)
        assertTrue("Answer must not be empty", res.answer.isNotBlank())
        assertTrue("Answer should mention Marina Beach", res.answer.contains("Marina", ignoreCase = true))
        assertTrue("Should be specific tip", res.isSpecificTip)
    }

    @Test
    fun test02_DestinationTimingKapaleeshwararTemple() = runBlocking {
        val q = "What are the timings of Kapaleeshwarar Temple?"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertTrue("Answer must mention Kapaleeshwarar", res!!.topic?.contains("Kapaleeshwarar", ignoreCase = true) == true)
        assertTrue("Answer must contain timings", res.answer.contains("5:30") || res.answer.contains("AM") || res.answer.contains("open", ignoreCase = true))
        assertTrue("Should be specific tip", res.isSpecificTip)
    }

    @Test
    fun test03_TransportTimingBus5CDeterministic() = runBlocking {
        val q = "When does bus 5C leave?"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertTrue("Must be deterministic MTC response", res!!.answer.isNotBlank())
        // Since no origin was specified, MTC dynamic endpoint returns multiple origins clarification
        assertTrue(
            "Must return clarification or schedule for route 5C",
            res.answer.contains("Route 5C", ignoreCase = true) || res.answer.contains("5C", ignoreCase = true)
        )
        // Transport responses are deterministic, not hallucinated AI
        assertFalse("MTC transport response should not be AI generated", res.isAiGenerated)
    }

    @Test
    fun test03b_TransportTimingBus5CWithOrigin() = runBlocking {
        val q = "What are the timings for 5C from Adyar?"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertTrue("Must return scheduled timings for 5C", res!!.answer.contains("05:00") || res.answer.contains("scheduled departures", ignoreCase = true))
        assertFalse("MTC transport schedule must not be AI generated", res.isAiGenerated)
    }

    @Test
    fun test04_EmergencyQueryDeterministic() = runBlocking {
        val q = "I am in an emergency"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertEquals("Emergency", res!!.topic)
        assertTrue("Emergency answer must state 112", res.answer.contains("112"))
        assertFalse("Emergency query must NEVER be AI generated (Gemma count = 0)", res.isAiGenerated)
    }

    @Test
    fun test05_EnglishAiSynthesisGemma() = runBlocking {
        val q = "What is the entry fee for Fort St. George?"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertTrue("Must return Fort St. George", res!!.topic?.contains("Fort", ignoreCase = true) == true)
        assertTrue("Must state Rs. 25 verified fee", res.answer.contains("25"))
        assertTrue("Should be AI synthesized", res.isAiGenerated)
    }

    @Test
    fun test06_TamilDestinationQuery() = runBlocking {
        val q = "மெரினா கடற்கரை பற்றி சொல்லுங்கள்"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertEquals("Marina Beach", res!!.topic)
        assertTrue("Answer must not be empty", res.answer.isNotBlank())
        // Verify script detection selects Tamil locale
        val detectedLocale = Speaker.detectLocale(res.answer)
        if (res.answer.any { it in '\u0B80'..'\u0BFF' }) {
            assertEquals("ta-IN", detectedLocale.toLanguageTag())
        }
    }

    @Test
    fun test07_HindiDestinationQuery() = runBlocking {
        val q = "मरीना बीच के बारे में बताएं"
        val res = client.ask(q)

        assertNotNull("Response should not be null", res)
        assertEquals("Marina Beach", res!!.topic)
        assertTrue("Answer must not be empty", res.answer.isNotBlank())
        // Verify script detection selects Hindi locale if answered in Hindi
        val detectedLocale = Speaker.detectLocale(res.answer)
        if (res.answer.any { it in '\u0900'..'\u097F' }) {
            assertEquals("hi-IN", detectedLocale.toLanguageTag())
        }
    }

    @Test
    fun test08_BackendUnavailableFallback() = runBlocking {
        val offlineClient = AiAnswerClient("http://127.0.0.1:9999/tourcompanion/api/ask.php")
        val res = offlineClient.ask("Tell me about Marina Beach.")

        // Must return null gracefully without throwing an exception or crashing
        org.junit.Assert.assertNull("Unavailable backend must return null to trigger offline fallback", res)
    }

    @Test
    fun test09_DestinationClassificationAndOfflineProtection() {
        // Known destination queries must be identified
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Marina Beach."))
        assertTrue(QueryProcessor.isDestinationQuery("What are the timings of Marina Beach?"))
        assertTrue(QueryProcessor.isDestinationQuery("How do I reach Kapaleeshwarar Temple?"))
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Fort St George."))
        assertTrue(QueryProcessor.isDestinationQuery("Tell me about Kapaleeshwarar Temple."))
        assertTrue(QueryProcessor.isDestinationQuery("மெரினா கடற்கரை பற்றி சொல்லுங்கள்"))
        assertTrue(QueryProcessor.isDestinationQuery("मरीना बीच के बारे में बताएं"))

        // General travel tips must NOT be classified as destinations
        assertFalse(QueryProcessor.isDestinationQuery("What should I do when swimming at a beach?"))
        assertFalse(QueryProcessor.isDestinationQuery("What should I do if I lose my wallet?"))
        assertFalse(QueryProcessor.isDestinationQuery("How should I stay safe at night?"))
        assertFalse(QueryProcessor.isDestinationQuery("What should I do at an ATM?"))
    }

    @Test
    fun test10_DestinationOfflineMessageConstant() {
        assertEquals(
            "Destination details require a connection to the travel database.",
            AnswerRepository.DESTINATION_OFFLINE_MESSAGE
        )
    }

    @Test
    fun test11_SpeakerLocaleDetection() {
        // English text
        val enLocale = Speaker.detectLocale("Marina Beach is an iconic natural urban beach.")
        assertEquals("en-IN", enLocale.toLanguageTag())

        // Tamil text
        val taLocale = Speaker.detectLocale("மெரினா கடற்கரை ஒரு அழகான கடற்கரை.")
        assertEquals("ta-IN", taLocale.toLanguageTag())

        // Hindi text
        val hiLocale = Speaker.detectLocale("मरीना बीच चेन्नई का एक प्रसिद्ध समुद्र तट है।")
        assertEquals("hi-IN", hiLocale.toLanguageTag())
    }

    @Test
    fun test12_BackendAnswerContractIntegrity() {
        val answer = BackendAnswer(
            question = "Sample question",
            topic = "Sample Topic",
            answer = "Sample Answer",
            isSpecificTip = true,
            isAiGenerated = true
        )

        assertEquals("Sample question", answer.question)
        assertEquals("Sample Topic", answer.topic)
        assertEquals("Sample Answer", answer.answer)
        assertTrue(answer.isSpecificTip)
        assertTrue(answer.isAiGenerated)
    }
}
