package com.example.tourcompanion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [MtcHtmlParser], [MtcTransportProvider], and MTC route timing integration.
 * Tests HTML parsing, routes 66, 5C, 21G, failure modes, timeouts, static fallback,
 * origin disambiguation, and removal of misleading live terminology.
 */
class MtcTransportProviderTest {

    // =========================================================================
    // 1. Parser Tests
    // =========================================================================

    @Test
    fun testParseOptionsOriginsAndDestinations() {
        val html = """
            <select name="selfrom" id="selfrom">
                <option value="">--Origin--</option>
                <option value="0">--Select--</option>
                <option value="POONAMALLEE">POONAMALLEE</option>
                <option value="TAMBARAM WEST">TAMBARAM WEST</option>
                <option value="IYYAPPANTHANGAL">IYYAPPANTHANGAL</option>
            </select>
            ^f377f0d9102f3ade66916c76e6769191
        """.trimIndent()

        val origins = MtcHtmlParser.parseOrigins(html)
        assertEquals(3, origins.size)
        assertEquals("POONAMALLEE", origins[0])
        assertEquals("TAMBARAM WEST", origins[1])
        assertEquals("IYYAPPANTHANGAL", origins[2])
    }

    @Test
    fun testParseTimingsFromStageClockHtml() {
        val html = """
            <div class="col-md-12 f-1p5" style="margin-top:30px;">
                <h6>ROYAPURAM B.S</h6>
                <div class="stage"><span><i class="fa fa-clock-o" style="color: #FF4800;"></i> 05:00</span></div>
                <div class="stage"><span><i class="fa fa-clock-o" style="color: #FF4800;"></i> 05:30</span></div>
                <div class="stage"><span><i class="fa fa-clock-o" style="color: #FF4800;"></i> 06:00</span></div>
                <div class="stage"><span><i class="fa fa-clock-o" style="color: #FF4800;"></i> 06:30</span></div>
            </div>^f377f0d9102f3ade66916c76e6769191
        """.trimIndent()

        val timings = MtcHtmlParser.parseTimings(html)
        assertEquals(4, timings.size)
        assertEquals("05:00", timings[0])
        assertEquals("05:30", timings[1])
        assertEquals("06:00", timings[2])
        assertEquals("06:30", timings[3])

        val heading = MtcHtmlParser.extractDestinationHeading(html)
        assertEquals("ROYAPURAM B.S", heading)

        val csrf = MtcHtmlParser.extractCsrfToken(html)
        assertEquals("f377f0d9102f3ade66916c76e6769191", csrf)
    }

    @Test
    fun testParseEmptyAndMalformedHtml() {
        assertTrue("Null HTML returns empty", MtcHtmlParser.parseOrigins(null).isEmpty())
        assertTrue("Empty HTML returns empty", MtcHtmlParser.parseOrigins("").isEmpty())
        assertTrue("No options returns empty", MtcHtmlParser.parseOrigins("<div>Random text</div>").isEmpty())
        assertTrue("Malformed timings return empty", MtcHtmlParser.parseTimings("<p>No times here</p>").isEmpty())
        assertNull("Malformed heading returns null", MtcHtmlParser.extractDestinationHeading("No heading"))
        assertNull("No CSRF returns null", MtcHtmlParser.extractCsrfToken("Plain text"))
    }

    // =========================================================================
    // 2. Route Tests: 66, 5C, 21G
    // =========================================================================

    @Test
    fun testRoute66DynamicLookup() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> {
                        Pair(200, """<select><option value="POONAMALLEE">POONAMALLEE</option></select>^token""")
                    }
                    url.contains("getdestinationrouteorigin") -> {
                        Pair(200, """<select><option value="TAMBARAM WEST">TAMBARAM WEST</option></select>^token""")
                    }
                    url.contains("ajaxbustimingsearch") -> {
                        Pair(200, """<div class="stage"><span><i class="fa fa-clock-o"></i> 09:12</span></div>^token""")
                    }
                    else -> Pair(404, null)
                }
            }
        )

        val res = provider.getRouteTiming("66")
        assertTrue("Route 66 should succeed", res is MtcTimingResult.Success)
        val timing = (res as MtcTimingResult.Success).timing
        assertEquals("66", timing.route)
        assertEquals("POONAMALLEE", timing.origin)
        assertEquals("TAMBARAM WEST", timing.destination)
        assertEquals(listOf("09:12"), timing.times)
        assertEquals("official_mtc_scheduled", timing.source)
    }

    @Test
    fun testRoute5CDynamicLookup() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> {
                        Pair(200, """<select><option value="ADYAR B.S">ADYAR B.S</option><option value="M.G.R.CENTRAL">M.G.R.CENTRAL</option></select>^token""")
                    }
                    url.contains("getdestinationrouteorigin") -> {
                        Pair(200, """<select><option value="ROYAPURAM B.S">ROYAPURAM B.S</option></select>^token""")
                    }
                    url.contains("ajaxbustimingsearch") -> {
                        Pair(200, """
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 05:00</span></div>
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 05:30</span></div>
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 06:00</span></div>
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 06:30</span></div>^token
                        """.trimIndent())
                    }
                    else -> Pair(404, null)
                }
            }
        )

        val res = provider.getRouteTiming("5C", origin = "Adyar", destination = "Royapuram")
        assertTrue(res is MtcTimingResult.Success)
        val timing = (res as MtcTimingResult.Success).timing
        assertEquals("5C", timing.route)
        assertEquals("ADYAR B.S", timing.origin)
        assertEquals("ROYAPURAM B.S", timing.destination)
        assertEquals(4, timing.times.size)
        assertEquals("05:00", timing.times[0])
        assertEquals("official_mtc_scheduled", timing.source)
    }

    @Test
    fun testRoute21GDynamicLookup() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> {
                        Pair(200, """<select><option value="FORESHORE ESTATE">FORESHORE ESTATE</option></select>^token""")
                    }
                    url.contains("getdestinationrouteorigin") -> {
                        Pair(200, """<select><option value="KCBT KILAMBAKKAM">KCBT KILAMBAKKAM</option></select>^token""")
                    }
                    url.contains("ajaxbustimingsearch") -> {
                        Pair(200, """
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 06:20</span></div>
                            <div class="stage"><span><i class="fa fa-clock-o"></i> 06:55</span></div>^token
                        """.trimIndent())
                    }
                    else -> Pair(404, null)
                }
            }
        )

        val res = provider.getRouteTiming("21G")
        assertTrue(res is MtcTimingResult.Success)
        val timing = (res as MtcTimingResult.Success).timing
        assertEquals("21G", timing.route)
        assertEquals(listOf("06:20", "06:55"), timing.times)
        assertEquals("official_mtc_scheduled", timing.source)
    }

    // =========================================================================
    // 3. Failure Tests: 500, 404, Timeout, Empty, No Origins/Destinations/Times
    // =========================================================================

    @Test
    fun testHttp500ReturnsError() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { Pair(500, "Server Error") }
        )
        val res = provider.getRouteTiming("5C")
        assertTrue(res is MtcTimingResult.NoData || res is MtcTimingResult.Error)
    }

    @Test
    fun testHttp404ReturnsNoData() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { Pair(404, "Not Found") }
        )
        val res = provider.getRouteTiming("5C")
        assertTrue(res is MtcTimingResult.NoData)
    }

    @Test
    fun testTimeoutReturnsError() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { throw IOException("Connection timed out") }
        )
        val res = provider.getRouteTiming("5C")
        assertTrue("Timeout must result in Error without crashing", res is MtcTimingResult.Error)
    }

    @Test
    fun testEmptyHtmlReturnsNoData() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { Pair(200, "") }
        )
        val res = provider.getRouteTiming("5C")
        assertTrue(res is MtcTimingResult.NoData)
    }

    @Test
    fun testNoDestinationsReturnsNoData() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                if (url.contains("getoriginbyroute")) {
                    Pair(200, """<select><option value="POONAMALLEE">POONAMALLEE</option></select>""")
                } else {
                    Pair(200, """<select></select>""")
                }
            }
        )
        val res = provider.getRouteTiming("66")
        assertTrue(res is MtcTimingResult.NoData)
    }

    @Test
    fun testNoTimingsInResponseReturnsNoData() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """<select><option value="A">A</option></select>""")
                    url.contains("getdestinationrouteorigin") -> Pair(200, """<select><option value="B">B</option></select>""")
                    else -> Pair(200, """<div>No stages here</div>""")
                }
            }
        )
        val res = provider.getRouteTiming("66")
        assertTrue(res is MtcTimingResult.NoData)
    }

    @Test
    fun testMetroRouteRejectedFromMtc() = runBlocking {
        val provider = MtcTransportProvider(backendProxyUrl = "")
        val res = provider.getRouteTiming("Blue Line")
        assertTrue(res is MtcTimingResult.NoData)
        assertTrue((res as MtcTimingResult.NoData).message.contains("only supports MTC bus routes"))
    }

    // =========================================================================
    // 4. Intent Classification & Integration Tests
    // =========================================================================

    @Test
    fun testTimingQueryClassification() {
        val intent1 = QueryProcessor.detectTransportIntent("What time is the next 5C bus from Adyar to Royapuram?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent1.subIntent)
        assertEquals("5C", intent1.target)
        assertEquals("Adyar", intent1.origin)
        assertEquals("Royapuram", intent1.destination)

        val intent2 = QueryProcessor.detectTransportIntent("When is the next 66 bus?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent2.subIntent)
        assertEquals("66", intent2.target)

        val intent3 = QueryProcessor.detectTransportIntent("bus timing for 21G")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent3.subIntent)
        assertEquals("21G", intent3.target)
    }

    @Test
    fun testMtcPriority1SuccessOutputFormat() = runBlocking {
        val mockProvider = object : TransportTimingProvider {
            override suspend fun getRouteTiming(route: String, origin: String?, destination: String?): MtcTimingResult {
                return MtcTimingResult.Success(
                    MtcScheduledTiming(
                        route = "5C",
                        origin = "ADYAR B.S",
                        destination = "ROYAPURAM B.S",
                        times = listOf("05:00", "05:30", "06:00", "06:30"),
                        source = "official_mtc_scheduled"
                    )
                )
            }
        }

        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
            target = "5C",
            origin = "Adyar",
            destination = "Royapuram"
        )

        val answer = QueryProcessor.processTransportQuery(
            intent = intent,
            repository = TransportRepository(),
            mtcProvider = mockProvider
        )

        assertTrue(answer.contains("Route 5C from ADYAR B.S to ROYAPURAM B.S has scheduled departures at 05:00, 05:30, 06:00, 06:30"))
        assertTrue(answer.contains("These are scheduled MTC departure timings retrieved from the MTC service and may change in actual operation"))
        assertFalse("Never claim live ETA", answer.contains("will arrive in"))
    }

    @Test
    fun testMtcFailureFallsBackToPriority2StaticRoutes() = runBlocking {
        val failingProvider = object : TransportTimingProvider {
            override suspend fun getRouteTiming(route: String, origin: String?, destination: String?): MtcTimingResult {
                return MtcTimingResult.Error("MTC endpoint timed out")
            }
        }

        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
            target = "66"
        )

        // Local static transport repository will be searched as fallback
        val answer = QueryProcessor.processTransportQuery(
            intent = intent,
            repository = TransportRepository(),
            mtcProvider = failingProvider
        )

        assertTrue(
            "Must fall back to static data or transparent fallback message",
            answer.contains("MTC schedule for route '66' right now")
        )
    }

    // =========================================================================
    // 5. Corrections Verification: Multiple Origins & Terminology
    // =========================================================================

    @Test
    fun testCorrection1MultipleOriginsWithoutOriginDoesNotSilentlyChooseFirst() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """
                        <select>
                            <option value="ADYAR B.S">ADYAR B.S</option>
                            <option value="M.G.R.CENTRAL">M.G.R.CENTRAL</option>
                            <option value="ROYAPURAM B.S">ROYAPURAM B.S</option>
                            <option value="SECRETARIAT">SECRETARIAT</option>
                            <option value="TARAMANI">TARAMANI</option>
                        </select>^token
                    """.trimIndent())
                    else -> Pair(404, null)
                }
            }
        )

        // Querying "5C" without origin must NOT silently choose ADYAR B.S
        val res = provider.getRouteTiming("5C")
        assertTrue("Must return MultipleOrigins when multiple origins exist", res is MtcTimingResult.MultipleOrigins)

        val multi = res as MtcTimingResult.MultipleOrigins
        assertEquals("5C", multi.route)
        assertEquals(5, multi.origins.size)
        assertTrue(multi.message.contains("Route 5C has multiple starting points"))
        assertTrue(multi.message.contains("Please tell me your starting point, such as Adyar or M.G.R. Central"))

        // Verify user-facing string via QueryProcessor
        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
            target = "5C"
        )
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = provider)
        assertEquals("Route 5C has multiple starting points. Please tell me your starting point, such as Adyar or M.G.R. Central.", answer)
    }

    @Test
    fun testCorrection1MultipleOriginsWithExplicitOriginSelectsMatchingOrigin() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """
                        <select>
                            <option value="ADYAR B.S">ADYAR B.S</option>
                            <option value="M.G.R.CENTRAL">M.G.R.CENTRAL</option>
                            <option value="ROYAPURAM B.S">ROYAPURAM B.S</option>
                        </select>^token
                    """.trimIndent())
                    url.contains("getdestinationrouteorigin") && url.contains("ADYAR") -> {
                        Pair(200, """<select><option value="ROYAPURAM B.S">ROYAPURAM B.S</option></select>^token""")
                    }
                    url.contains("ajaxbustimingsearch") -> {
                        Pair(200, """<div class="stage"><span><i class="fa fa-clock-o"></i> 05:00</span></div>^token""")
                    }
                    else -> Pair(404, null)
                }
            }
        )

        val res = provider.getRouteTiming("5C", origin = "Adyar")
        assertTrue("Must succeed when explicit origin is provided", res is MtcTimingResult.Success)
        val timing = (res as MtcTimingResult.Success).timing
        assertEquals("5C", timing.route)
        assertEquals("ADYAR B.S", timing.origin)
        assertEquals("ROYAPURAM B.S", timing.destination)
        assertEquals(listOf("05:00"), timing.times)
    }

    @Test
    fun testCorrection1SingleOriginRouteAutomaticallySelected() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """<select><option value="POONAMALLEE">POONAMALLEE</option></select>^token""")
                    url.contains("getdestinationrouteorigin") -> Pair(200, """<select><option value="TAMBARAM WEST">TAMBARAM WEST</option></select>^token""")
                    url.contains("ajaxbustimingsearch") -> Pair(200, """<div class="stage"><span><i class="fa fa-clock-o"></i> 09:12</span></div>^token""")
                    else -> Pair(404, null)
                }
            }
        )

        // Route with exactly 1 origin: auto-selection is permitted
        val res = provider.getRouteTiming("66")
        assertTrue(res is MtcTimingResult.Success)
        val timing = (res as MtcTimingResult.Success).timing
        assertEquals("POONAMALLEE", timing.origin)
        assertEquals("TAMBARAM WEST", timing.destination)
    }

    @Test
    fun testCorrection2SourceLabelIsOfficialMtcScheduledNotLive() = runBlocking {
        val timing = MtcScheduledTiming(
            route = "5C",
            origin = "ADYAR B.S",
            destination = "ROYAPURAM B.S",
            times = listOf("05:00")
        )
        assertEquals("official_mtc_scheduled", timing.source)
        assertFalse("Source label must not say live", timing.source.contains("live", ignoreCase = true))

        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """<select><option value="POONAMALLEE">POONAMALLEE</option></select>^token""")
                    url.contains("getdestinationrouteorigin") -> Pair(200, """<select><option value="TAMBARAM WEST">TAMBARAM WEST</option></select>^token""")
                    url.contains("ajaxbustimingsearch") -> Pair(200, """<div class="stage"><span><i class="fa fa-clock-o"></i> 09:12</span></div>^token""")
                    else -> Pair(404, null)
                }
            }
        )
        val res = provider.getRouteTiming("66")
        assertTrue(res is MtcTimingResult.Success)
        val retrieved = (res as MtcTimingResult.Success).timing
        assertEquals("official_mtc_scheduled", retrieved.source)
        assertFalse(retrieved.source.contains("live", ignoreCase = true))
    }

    @Test
    fun testCorrection2UserFacingResponseDoesNotClaimLiveGpsOrEta() = runBlocking {
        val mockProvider = object : TransportTimingProvider {
            override suspend fun getRouteTiming(route: String, origin: String?, destination: String?): MtcTimingResult {
                return MtcTimingResult.Success(
                    MtcScheduledTiming(
                        route = "5C",
                        origin = "ADYAR B.S",
                        destination = "ROYAPURAM B.S",
                        times = listOf("05:00", "05:30")
                    )
                )
            }
        }

        val intent = TransportIntent(
            subIntent = TransportSubIntent.TRANSPORT_ROUTE_TIMING,
            target = "5C",
            origin = "Adyar",
            destination = "Royapuram"
        )
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = mockProvider)

        assertTrue("Must include scheduled disclaimer", answer.contains("These are scheduled MTC departure timings retrieved from the MTC service and may change in actual operation."))
        assertFalse("Must not claim live bus timing", answer.contains("Live bus timing", ignoreCase = true))
        assertFalse("Must not claim live bus location", answer.contains("Live bus location", ignoreCase = true))
        assertFalse("Must not claim live MTC timing", answer.contains("live MTC timing", ignoreCase = true))
        assertFalse("Must not claim arrival ETA in minutes", answer.contains("will arrive in", ignoreCase = true))
    }

    // =========================================================================
    // 6. Ten Runtime Verification Scenarios
    // =========================================================================

    @Test
    fun testScenario1KnownMtcRouteTimingQuery() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """<select><option value="ADYAR B.S">ADYAR B.S</option></select>""")
                    url.contains("getdestinationrouteorigin") -> Pair(200, """<select><option value="ROYAPURAM B.S">ROYAPURAM B.S</option></select>""")
                    else -> Pair(200, """<div class="stage"><span><i class="fa fa-clock-o"></i> 05:00</span></div>""")
                }
            }
        )
        val intent = QueryProcessor.detectTransportIntent("What time is the next 5C bus from Adyar to Royapuram?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent.subIntent)
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = provider)
        assertTrue(answer.contains("Route 5C"))
        assertTrue(answer.contains("ADYAR B.S"))
        assertTrue(answer.contains("ROYAPURAM B.S"))
        assertTrue(answer.contains("scheduled departures at 05:00"))
    }

    @Test
    fun testScenario2MultipleScheduledTimes() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { url ->
                when {
                    url.contains("getoriginbyroute") -> Pair(200, """<select><option value="ADYAR B.S">ADYAR B.S</option></select>""")
                    url.contains("getdestinationrouteorigin") -> Pair(200, """<select><option value="ROYAPURAM B.S">ROYAPURAM B.S</option></select>""")
                    else -> Pair(200, """
                        <div class="stage"><span><i class="fa fa-clock-o"></i> 05:00</span></div>
                        <div class="stage"><span><i class="fa fa-clock-o"></i> 05:30</span></div>
                        <div class="stage"><span><i class="fa fa-clock-o"></i> 06:00</span></div>
                    """.trimIndent())
                }
            }
        )
        val intent = QueryProcessor.detectTransportIntent("What is the timing of bus 5C?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent.subIntent)
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = provider)
        assertTrue(answer.contains("05:00, 05:30, 06:00"))
        assertTrue(answer.contains("These are scheduled MTC departure timings retrieved from the MTC service and may change in actual operation"))
    }

    @Test
    fun testScenario3InvalidRoute() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { Pair(200, "<select></select>") }
        )
        val intent = QueryProcessor.detectTransportIntent("What time is route 9999999?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent.subIntent)
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = provider)
        assertTrue(answer.contains("I couldn't get the current MTC schedule for route '9999999' right now"))
    }

    @Test
    fun testScenario4MtcEndpointFailureFallbackCase() = runBlocking {
        val provider = MtcTransportProvider(
            backendProxyUrl = "",
            customHttpFetcher = { throw IOException("Connection reset by peer") }
        )
        val intent = QueryProcessor.detectTransportIntent("What time is bus 5C?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent.subIntent)
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), mtcProvider = provider)
        assertTrue(answer.contains("MTC schedule for route '5C' right now"))
    }

    @Test
    fun testScenario5NearbyTransport() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("Where is the nearest bus stop?")
        assertEquals(TransportSubIntent.TRANSPORT_NEARBY, intent.subIntent)
        assertEquals("bus", intent.mode)
        val answer = QueryProcessor.processTransportQuery(intent, TransportRepository(), userLat = null, userLon = null)
        assertTrue("Nearby transport requires user coordinates", answer.contains("location coordinates"))
    }

    @Test
    fun testScenario6MetroRouteQuery() = runBlocking {
        val intent = QueryProcessor.detectTransportIntent("What is the Blue Line metro?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_SEARCH, intent.subIntent)
        assertEquals("subway", intent.mode)
        assertEquals("Blue", intent.target)
    }

    @Test
    fun testScenario7DestinationQuery() {
        // Destination query must NOT be hijacked by transport intent
        val intent = QueryProcessor.detectTransportIntent("Tell me about Marina Beach")
        assertEquals(TransportSubIntent.NONE, intent.subIntent)
    }

    @Test
    fun testScenario8SafetyQuery() {
        // Safety questions must NOT be hijacked by transport intent
        val intent = QueryProcessor.detectTransportIntent("Is it safe at night?")
        assertEquals(TransportSubIntent.NONE, intent.subIntent)
    }

    @Test
    fun testScenario9EmergencyQuery() {
        // Emergency/distress must NEVER be classified as transport
        val intent1 = QueryProcessor.detectTransportIntent("SOS save me")
        val intent2 = QueryProcessor.detectTransportIntent("someone is following me")
        assertEquals(TransportSubIntent.NONE, intent1.subIntent)
        assertEquals(TransportSubIntent.NONE, intent2.subIntent)
    }

    @Test
    fun testScenario10MicrophoneVoicePromptIntegrity() {
        val intent = QueryProcessor.detectTransportIntent("What time is bus 66?")
        assertEquals(TransportSubIntent.TRANSPORT_ROUTE_TIMING, intent.subIntent)
        assertEquals("66", intent.target)
    }
}
