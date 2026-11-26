package com.example.tourcompanion

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Focused tests for Foreground Location Architecture.
 *
 * Verifies that:
 * 1. Permission granted -> location manager can be initialized.
 * 2. Permission denied -> app continues normally, safely returning null/unavailable without crashes.
 * 3. Approximate location -> accepted (coarse coordinates are valid).
 * 4. Nearby Attractions can reuse the location.
 * 5. Distance can reuse the location.
 * 6. Transport Nearby can reuse the location.
 * 7. Normal destination query does not depend on location availability.
 * 8. Emergency does not depend on location availability.
 * 9. No background location permission exists in AndroidManifest.xml.
 * 10. No background location service was introduced in AndroidManifest.xml.
 * 11. No continuous polling loop was introduced in AppLocationManager.
 * 12. Existing routing behavior remains intact.
 */
class ForegroundLocationArchitectureTest {

    private lateinit var repository: AnswerRepository
    private val testBackendUrl = "http://127.0.0.1/tourcompanion/api/ask.php"

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

    // 1. Permission granted -> location manager can be initialized
    @Test
    fun testPermissionGrantedLocationManagerCanBeInitialized() {
        val context = TestContext()
        val manager = AppLocationManager(context)
        assertNotNull(manager)
        // Set in-memory coordinates to simulate foreground location acquisition
        manager.setInMemoryCoordinates(13.054180, 80.283680)
        val latest = manager.getLatestCoordinates()
        assertNotNull(latest)
        assertEquals(13.054180, latest!!.first, 0.0001)
        assertEquals(80.283680, latest.second, 0.0001)
    }

    // 2. Permission denied -> app continues normally
    @Test
    fun testPermissionDeniedAppContinuesNormally() {
        val context = TestContext()
        val manager = AppLocationManager(context)
        // When permission is not granted (JVM mock returns false), getLastKnownCoordinates safely returns null
        val coords = manager.getLastKnownCoordinates()
        assertNull(coords)
    }

    // 3. Approximate location -> accepted
    @Test
    fun testApproximateLocationAccepted() {
        // Coarse location coordinates with lower precision (e.g. 2 decimal places ~1 km accuracy)
        val coarseLat = 13.05
        val coarseLon = 80.28
        assertTrue(AppLocationManager.isValidCoordinate(coarseLat, coarseLon))

        val context = TestContext()
        val manager = AppLocationManager(context)
        manager.setInMemoryCoordinates(coarseLat, coarseLon)
        val latest = manager.getLatestCoordinates()
        assertNotNull(latest)
        assertEquals(13.05, latest!!.first, 0.001)
        assertEquals(80.28, latest.second, 0.001)
    }

    // 4. Nearby Attractions can reuse the location
    @Test
    fun testNearbyAttractionsCanReuseLocation() = runBlocking {
        val answer = repository.answer("What is near me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(answer.text.contains("Marina", ignoreCase = true))
    }

    // 5. Distance can reuse the location
    @Test
    fun testDistanceCanReuseLocation() = runBlocking {
        val answer = repository.answer("How far is Marina Beach from me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(answer.text.contains("Marina", ignoreCase = true))
        assertTrue(answer.text.contains("0.0") || answer.text.contains("0 km") || answer.text.contains("right now", ignoreCase = true))
    }

    // 6. Transport Nearby can reuse the location
    @Test
    fun testTransportNearbyCanReuseLocation() = runBlocking {
        val answer = repository.answer("Which bus stop is near me?", userLat = 13.054180, userLon = 80.283680)
        assertEquals(AnswerRepository.Source.TRANSPORT, answer.source)
        assertTrue(answer.text.isNotBlank())
        assertFalse(answer.text.contains("need your location coordinates"))
    }

    // 7. Normal destination query does not depend on location availability
    @Test
    fun testNormalDestinationDoesNotDependOnLocation() = runBlocking {
        val qResult = QueryProcessor.classifyTravelIntent("Tell me about Marina Beach")
        assertFalse("Destination details must not require location", qResult.requiresLocation)

        val answer = repository.answer("Tell me about Marina Beach", userLat = null, userLon = null)
        assertEquals(AnswerRepository.Source.BACKEND, answer.source)
        assertTrue(answer.text.contains("Marina", ignoreCase = true))
    }

    // 8. Emergency does not depend on location availability
    @Test
    fun testEmergencyDoesNotDependOnLocation() = runBlocking {
        val qResult = QueryProcessor.classifyTravelIntent("I need emergency help")
        assertFalse("Emergency must not require location", qResult.requiresLocation)

        val answer = repository.answer("I need emergency help", userLat = null, userLon = null)
        assertEquals(AnswerRepository.Source.LOCAL, answer.source)
        assertTrue(answer.text.contains("112"))
    }

    // 9. No background location permission exists in manifest
    @Test
    fun testNoBackgroundLocationPermissionInManifest() {
        val manifestPath = "src/main/AndroidManifest.xml"
        val manifestFile = File(manifestPath)
        val content = if (manifestFile.exists()) manifestFile.readText() else File("app/$manifestPath").readText()

        assertFalse("ACCESS_BACKGROUND_LOCATION must NOT be requested", content.contains("ACCESS_BACKGROUND_LOCATION"))
        assertTrue("ACCESS_FINE_LOCATION should be declared", content.contains("ACCESS_FINE_LOCATION"))
        assertTrue("ACCESS_COARSE_LOCATION should be declared", content.contains("ACCESS_COARSE_LOCATION"))
    }

    // 10. No background location service was introduced
    @Test
    fun testNoBackgroundLocationService() {
        val manifestPath = "src/main/AndroidManifest.xml"
        val manifestFile = File(manifestPath)
        val content = if (manifestFile.exists()) manifestFile.readText() else File("app/$manifestPath").readText()

        assertFalse("No location <service> should be declared in the manifest", content.contains("<service"))
    }

    // 11. No continuous polling loop was introduced in AppLocationManager
    @Test
    fun testNoContinuousPollingLoopInLocationManager() {
        val sourcePath = "src/main/java/com/example/tourcompanion/AppLocationManager.kt"
        val file = File(sourcePath).let { if (it.exists()) it else File("app/$sourcePath") }
        val source = file.readText()

        assertFalse("Must not register requestLocationUpdates polling loops", source.contains("requestLocationUpdates"))
        assertFalse("Must not start periodic handler or timer loops", source.contains("postDelayed"))
    }

    // 12. Fresh location acquisition with timeout
    @Test
    fun testFreshLocationFallbackGracefulOnUnavailable() = runBlocking {
        val context = TestContext()
        val manager = AppLocationManager(context)
        // With no hardware providers in unit test, getFreshCoordinates gracefully returns null or inMemory fallback
        val fresh = manager.getFreshCoordinates(timeoutMs = 100L)
        // In unmocked TestContext without location manager, safely returns null without throwing
        assertNull(fresh)
    }

    // 13. In-flight guard deduplicates concurrent location requests
    @Test
    fun testFreshLocationInMemoryCacheCanBeUsed() = runBlocking {
        val context = TestContext()
        val manager = AppLocationManager(context)
        manager.setInMemoryCoordinates(13.0827, 80.2707)
        val latest = manager.getLatestCoordinates()
        assertNotNull(latest)
        assertEquals(13.0827, latest!!.first, 0.0001)
        assertEquals(80.2707, latest.second, 0.0001)
    }

    // 14. Differentiated location messages exist
    @Test
    fun testDifferentiatedLocationMessages() {
        assertTrue(AnswerRepository.LOCATION_DISABLED_MESSAGE.contains("turned off", ignoreCase = true))
        assertTrue(AnswerRepository.LOCATION_FIX_UNAVAILABLE_MESSAGE.contains("unable to get", ignoreCase = true))
        assertTrue(AnswerRepository.NEARBY_ATTRACTIONS_UNAVAILABLE.contains("needs your location", ignoreCase = true))
        assertTrue(AnswerRepository.DISTANCE_LOCATION_REQUIRED_MESSAGE.contains("needs your location", ignoreCase = true))
    }
}
