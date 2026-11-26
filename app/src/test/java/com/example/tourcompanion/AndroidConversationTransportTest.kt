package com.example.tourcompanion

import android.content.Context
import android.content.ContextWrapper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class AndroidConversationTransportTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val client = AiAnswerClient("http://127.0.0.1:0/ask.php")

    @Before
    fun setUp() {
        QueryProcessor.clearSessionDestination()
    }

    @Test
    fun test01_emptyHistorySerializedCorrectly() {
        val body = client.buildAskRequestBody(
            question = "Tell me about Marina Beach",
            conversationHistory = emptyList()
        )
        assertEquals("Tell me about Marina Beach", body.getString("question"))
        assertTrue(body.has("conversation_history"))
        val arr = body.getJSONArray("conversation_history")
        assertEquals(0, arr.length())
    }

    @Test
    fun test02_userAndAssistantPairPreserved() {
        val history = listOf(
            ConversationMessage(ChatRole.USER, "Tell me about Marina Beach"),
            ConversationMessage(ChatRole.ASSISTANT, "Marina Beach is an iconic urban beach in Chennai.")
        )
        val body = client.buildAskRequestBody(
            question = "What can I do there?",
            conversationHistory = history
        )
        val arr = body.getJSONArray("conversation_history")
        assertEquals(2, arr.length())

        val first = arr.getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("Tell me about Marina Beach", first.getString("text"))

        val second = arr.getJSONObject(1)
        assertEquals("assistant", second.getString("role"))
        assertEquals("Marina Beach is an iconic urban beach in Chennai.", second.getString("text"))
    }

    @Test
    fun test03_multipleMessagesPreserveChronologicalOrder() {
        val history = listOf(
            ConversationMessage(ChatRole.USER, "U1"),
            ConversationMessage(ChatRole.ASSISTANT, "A1"),
            ConversationMessage(ChatRole.USER, "U2"),
            ConversationMessage(ChatRole.ASSISTANT, "A2"),
            ConversationMessage(ChatRole.USER, "U3"),
            ConversationMessage(ChatRole.ASSISTANT, "A3")
        )
        val body = client.buildAskRequestBody(
            question = "Follow up",
            conversationHistory = history
        )
        val arr = body.getJSONArray("conversation_history")
        assertEquals(6, arr.length())

        val expectedRoles = listOf("user", "assistant", "user", "assistant", "user", "assistant")
        val expectedTexts = listOf("U1", "A1", "U2", "A2", "U3", "A3")
        for (i in 0 until 6) {
            val obj = arr.getJSONObject(i)
            assertEquals(expectedRoles[i], obj.getString("role"))
            assertEquals(expectedTexts[i], obj.getString("text"))
        }
    }

    @Test
    fun test04_historySnapshotIsImmutableAfterCapture() {
        val liveHistory = ConversationHistory()
        liveHistory.addUserMessage("Q1")
        liveHistory.addAssistantMessage("A1")
        val snapshot = liveHistory.messages()

        // Mutate liveHistory after taking snapshot
        liveHistory.addUserMessage("Q2")
        liveHistory.addAssistantMessage("A2")

        assertEquals(2, snapshot.size)
        assertEquals(4, liveHistory.size())
    }

    @Test
    fun test05_answerRepositoryWorksWithEmptyHistory() = runBlocking {
        val repo = AnswerRepository(
            context = TestContext(),
            remote = AiAnswerClient("")
        )
        val ans = repo.answer("Hello", conversationHistory = emptyList())
        assertEquals(AnswerRepository.Source.SMALL_TALK, ans.source)
        assertTrue(ans.text.isNotBlank())
    }

    @Test
    fun test06_answerRepositoryForwardsHistoryToAiAnswerClient() = runBlocking {
        var capturedRequestJson: JSONObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ask.php") { exchange ->
            val rawBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            capturedRequestJson = JSONObject(rawBody)
            val respBytes = """{"status":"ok","topic":"Marina Beach","answer":"Open 24 hours.","ai_generated":true}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, respBytes.size.toLong())
            exchange.responseBody.use { it.write(respBytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val repo = AnswerRepository(
                context = TestContext(),
                remote = AiAnswerClient("http://127.0.0.1:$port/ask.php")
            )
            val history = listOf(
                ConversationMessage(ChatRole.USER, "Tell me about Marina Beach"),
                ConversationMessage(ChatRole.ASSISTANT, "Marina Beach is in Chennai."),
                ConversationMessage(ChatRole.USER, "What are its timings?")
            )

            val ans = repo.answer(
                question = "What are its timings?",
                conversationHistory = history
            )
            assertEquals(AnswerRepository.Source.BACKEND, ans.source)
            assertEquals("Open 24 hours.", ans.text)

            assertNotNull("Expected HTTP request JSON to be captured", capturedRequestJson)
            val reqArr = capturedRequestJson!!.getJSONArray("conversation_history")
            assertEquals(3, reqArr.length())
            assertEquals("user", reqArr.getJSONObject(0).getString("role"))
            assertEquals("Tell me about Marina Beach", reqArr.getJSONObject(0).getString("text"))
            assertEquals("assistant", reqArr.getJSONObject(1).getString("role"))
            assertEquals("Marina Beach is in Chennai.", reqArr.getJSONObject(1).getString("text"))
            assertEquals("user", reqArr.getJSONObject(2).getString("role"))
            assertEquals("What are its timings?", reqArr.getJSONObject(2).getString("text"))
        } finally {
            server.stop(0)
        }
    }
}
