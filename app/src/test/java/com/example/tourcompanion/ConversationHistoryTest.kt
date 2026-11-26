package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationHistoryTest {

    private lateinit var history: ConversationHistory

    @Before
    fun setUp() {
        history = ConversationHistory()
    }

    @Test
    fun initiallyEmpty() {
        assertTrue(history.isEmpty())
        assertEquals(0, history.size())
        assertTrue(history.messages().isEmpty())
    }

    @Test
    fun userMessageAdded() {
        history.addUserMessage("Tell me about Marina Beach")
        val list = history.messages()
        assertEquals(1, list.size)
        assertEquals(ChatRole.USER, list[0].role)
        assertEquals("Tell me about Marina Beach", list[0].text)
    }

    @Test
    fun assistantMessageAdded() {
        history.addAssistantMessage("Marina Beach is a long urban beach in Chennai.")
        val list = history.messages()
        assertEquals(1, list.size)
        assertEquals(ChatRole.ASSISTANT, list[0].role)
        assertEquals("Marina Beach is a long urban beach in Chennai.", list[0].text)
    }

    @Test
    fun orderingPreserved() {
        history.addUserMessage("Q1")
        history.addAssistantMessage("A1")
        history.addUserMessage("Q2")
        history.addAssistantMessage("A2")

        val list = history.messages()
        assertEquals(4, list.size)
        assertEquals(ConversationMessage(ChatRole.USER, "Q1"), list[0])
        assertEquals(ConversationMessage(ChatRole.ASSISTANT, "A1"), list[1])
        assertEquals(ConversationMessage(ChatRole.USER, "Q2"), list[2])
        assertEquals(ConversationMessage(ChatRole.ASSISTANT, "A2"), list[3])
    }

    @Test
    fun maximumSixMessagesEnforcedAndOldestRemoved() {
        history.addUserMessage("U1")
        history.addAssistantMessage("A1")
        history.addUserMessage("U2")
        history.addAssistantMessage("A2")
        history.addUserMessage("U3")
        history.addAssistantMessage("A3")
        assertEquals(6, history.size())

        // Adding a 4th turn should evict the oldest messages ("U1", "A1")
        history.addUserMessage("U4")
        history.addAssistantMessage("A4")

        val list = history.messages()
        assertEquals(6, list.size)
        assertEquals(ConversationMessage(ChatRole.USER, "U2"), list[0])
        assertEquals(ConversationMessage(ChatRole.ASSISTANT, "A2"), list[1])
        assertEquals(ConversationMessage(ChatRole.USER, "U3"), list[2])
        assertEquals(ConversationMessage(ChatRole.ASSISTANT, "A3"), list[3])
        assertEquals(ConversationMessage(ChatRole.USER, "U4"), list[4])
        assertEquals(ConversationMessage(ChatRole.ASSISTANT, "A4"), list[5])
    }

    @Test
    fun clearRemovesAllMessages() {
        history.addUserMessage("U1")
        history.addAssistantMessage("A1")
        assertEquals(2, history.size())

        history.clear()
        assertTrue(history.isEmpty())
        assertEquals(0, history.size())
        assertTrue(history.messages().isEmpty())
    }
}
