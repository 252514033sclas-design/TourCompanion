package com.example.tourcompanion

/**
 * Role of a message in an active AskSemmozhiActivity conversation session.
 */
enum class ChatRole {
    USER,
    ASSISTANT
}

/**
 * A single turn entry stored in the in-memory session conversation history.
 */
data class ConversationMessage(
    val role: ChatRole,
    val text: String
)

/**
 * Lightweight, session-scoped in-memory buffer of recent user and assistant turns.
 * Keeps only the most recent [maxMessages] entries (default 6 = 3 user + 3 assistant turns).
 */
class ConversationHistory(
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES
) {
    private val items = ArrayDeque<ConversationMessage>()

    @Synchronized
    fun addUserMessage(text: String) {
        if (text.isEmpty()) return
        append(ConversationMessage(ChatRole.USER, text))
    }

    @Synchronized
    fun addAssistantMessage(text: String) {
        if (text.isEmpty()) return
        append(ConversationMessage(ChatRole.ASSISTANT, text))
    }

    @Synchronized
    fun messages(): List<ConversationMessage> = items.toList()

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    @Synchronized
    fun clear() {
        items.clear()
    }

    private fun append(message: ConversationMessage) {
        if (maxMessages <= 0) return
        while (items.size >= maxMessages) {
            items.removeFirst()
        }
        items.addLast(message)
    }

    companion object {
        const val DEFAULT_MAX_MESSAGES = 6
    }
}
