package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit and integration tests for the static Chennai transport data layer.
 * Tests TransportApiClient, TransportRepository, and response parsing.
 */
class TransportDataTest {

    // In local plain JVM test, use localhost or the configured BASE_URL
    private val testBaseUrl = "http://localhost/tourcompanion/api/"
    private val apiClient = TransportApiClient(testBaseUrl)

    @Test
    fun testTransportModelsAndNullableAgency() {
        val cmrlRoute = TransportRoute(
            routeId = "CMRL-102101",
            agencyId = null,
            agencyName = "Chennai Metro Rail Limited",
            routeShortName = "SAP-SWN",
            routeLongName = "Airport - Wimco Nagar Metro",
            routeType = 1,
            routeDesc = null,
            routeColor = "000092",
            routeTextColor = "FFFFFF"
        )
        assertNull("agencyId must be null for unassigned CMRL routes", cmrlRoute.agencyId)
        assertEquals("CMRL-102101", cmrlRoute.routeId)
        assertEquals("000092", cmrlRoute.routeColor)

        val agency = TransportAgency("69", "MTC", "Metropolitan Transport Corporation")
        val stop = TransportStop(
            stopId = "MTC-840",
            stopName = "Broadway",
            stopLat = 13.08762,
            stopLon = 80.28383,
            locationType = 0,
            parentStation = null,
            distanceKm = 0.45,
            agency = agency
        )
        assertNull("parentStation can be null", stop.parentStation)
        assertEquals("69", stop.agency?.agencyId)
    }

    @Test
    fun testNearbyStopsMGRCentral() = runBlocking {
        // Lat: 13.0814, Lon: 80.2729 (MGR Central Metro area), radius = 1 km
        val response = apiClient.getNearbyStops(13.0814, 80.2729, 1.0)
        assertTrue("Nearby stops request should succeed", response is TransportApiClient.Response.Success)

        val success = response as TransportApiClient.Response.Success
        assertTrue("Should return stops around Central", success.data.isNotEmpty())

        val nearest = success.data.first()
        assertTrue("Nearest stop should be within 0.1 km", nearest.distanceKm < 0.1)
        assertTrue(
            "Nearest stop name should mention Central Metro",
            nearest.stopName.contains("CENTRAL METRO", ignoreCase = true)
        )
    }

    @Test
    fun testNearbyStopsSmallRadius() = runBlocking {
        val rLarge = apiClient.getNearbyStops(13.0814, 80.2729, 2.0) as TransportApiClient.Response.Success
        val rSmall = apiClient.getNearbyStops(13.0814, 80.2729, 0.2) as TransportApiClient.Response.Success

        assertTrue("Smaller radius should return fewer or equal stops", rSmall.count <= rLarge.count)
        assertTrue("Small radius should still find stops within 200m of Central", rSmall.count > 0)
    }

    @Test
    fun testRouteSearch5C() = runBlocking {
        val response = apiClient.searchRoutes(query = "5C")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertTrue("5C search should return multiple route variants", success.count > 1)
        assertTrue(success.data.any { it.routeShortName.equals("5C", ignoreCase = true) })
    }

    @Test
    fun testRouteSearch21G() = runBlocking {
        val response = apiClient.searchRoutes(query = "21G")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertTrue("21G search should return multiple route variants", success.count > 1)
        assertTrue(success.data.any { it.routeShortName.equals("21G", ignoreCase = true) })
    }

    @Test
    fun testRouteSearchBlueLine() = runBlocking {
        val response = apiClient.searchRoutes(query = "Blue")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertTrue("Blue search should return CMRL Blue line routes", success.count > 0)

        val blueRoute = success.data.first()
        assertEquals("000092", blueRoute.routeColor)
        // Verify CMRL route with null agency_id parses without crashing
        assertNull("CMRL route agencyId should be null", blueRoute.agencyId)
        assertEquals("Chennai Metro Rail Limited", blueRoute.agencyName)
    }

    @Test
    fun testRouteSearchGreenLine() = runBlocking {
        val response = apiClient.searchRoutes(query = "Green")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertTrue("Green search should return CMRL Green line routes", success.count > 0)
        assertTrue(success.data.any { it.routeColor == "00A700" })
    }

    @Test
    fun testEmptyRouteSearch() = runBlocking {
        val response = apiClient.searchRoutes(query = "NONEXISTENT_ROUTE_XYZ_99999")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertEquals(0, success.count)
        assertTrue(success.data.isEmpty())
    }

    @Test
    fun testRouteForStopUnavailableNotice() = runBlocking {
        val response = apiClient.getRoutesForStop("MTC-840")
        assertTrue(response is TransportApiClient.Response.Success)
        val success = response as TransportApiClient.Response.Success
        assertEquals(0, success.count)
        assertNotNull(success.message)
        assertTrue(success.message!!.contains("not available in the current static dataset", ignoreCase = true))
    }

    @Test
    fun testNetworkFailureHandling() = runBlocking {
        // Point client to an unreachable port
        val unreachableClient = TransportApiClient("http://127.0.0.1:59999/api/")
        val response = unreachableClient.getNearbyStops(13.0814, 80.2729)
        assertTrue("Unreachable network should return Failure, never crash", response is TransportApiClient.Response.Failure)
    }

    @Test
    fun testRepositoryResultStates() = runBlocking {
        // Mocking / using repository wrapper logic
        val repo = TransportRepository(
            api = apiClient
        )

        // Success state
        val stopsResult = repo.getNearbyStops(13.0814, 80.2729, 1.0)
        assertTrue("Repo should return TransportResult.Success", stopsResult is TransportResult.Success)

        // Empty state
        val emptyResult = repo.searchRoutes(query = "NONEXISTENT_ROUTE_XYZ_99999")
        assertTrue("Repo should return TransportResult.Empty", emptyResult is TransportResult.Empty)

        // Notice state for route-to-stop mapping
        val noticeResult = repo.getRoutesForStop("MTC-840")
        assertTrue("Repo should return TransportResult.Notice", noticeResult is TransportResult.Notice)

        // Network unavailable state
        val unreachableRepo = TransportRepository(
            api = TransportApiClient("http://127.0.0.1:59999/api/")
        )
        val failedResult = unreachableRepo.getNearbyStops(13.0814, 80.2729)
        assertTrue("Repo should return TransportResult.NetworkUnavailable", failedResult is TransportResult.NetworkUnavailable)
    }
}
