package com.example.tourcompanion

import java.util.Locale

/**
 * Friendly conversation for short, whole-sentence messages (greetings, thanks, "who are you").
 * Each intent has several replies and one is chosen at random, so Semmozhi does not sound robotic.
 * Pure Kotlin, so it can be tested without a phone.
 */
object SmallTalk {

    private class Intent(val pattern: Regex, val replies: List<String>)

    private val intents = listOf(

        Intent(
            Regex("^(hi|hii|hello|hey|vanakkam|namaste|namaskar|good (morning|afternoon|evening))( there| semmozhi)?$"),
            listOf(
                "Vanakkam! I'm Semmozhi, your travel companion. How can I help you today?",
                "Hello! Ask me anything about getting around, staying safe, money, food or phrases.",
                "Namaste! Where are you heading today?",
                "Hi there! I'm here to help you travel safely and comfortably."
            )
        ),

        Intent(
            Regex("^(thanks|thank you|thank you so much|thx|nandri|dhanyavaad|dhanyavad)( semmozhi| a lot| very much)?$"),
            listOf(
                "You're most welcome! Safe travels.",
                "Happy to help! Ask me anything else anytime.",
                "Anytime! Enjoy your journey.",
                "Nandri! Let me know if you need anything else."
            )
        ),

        Intent(
            Regex("^(bye|goodbye|see you|see you later|good night|tata|take care)( semmozhi)?$"),
            listOf(
                "Goodbye! Stay safe and enjoy your trip.",
                "See you soon! Remember, 112 is there if you ever need urgent help.",
                "Take care, and travel safe!"
            )
        ),

        Intent(
            Regex("^(who are you|what are you|what is your name|whats your name|your name|are you a robot|are you real|tell me about yourself)$"),
            listOf(
                "I'm Semmozhi, a friendly virtual travel companion. I know about transport, money, food, hotels, health, safety and local customs.",
                "My name is Semmozhi. I'm an app character here to help you travel safely, and I can teach you Tamil and Hindi phrases too."
            )
        ),

        Intent(
            Regex("^(what can you do|how can you help|how can you help me|what do you know|what are your features|menu|options)$"),
            listOf(
                "I can answer travel questions, teach Tamil and Hindi phrases, show important helpline numbers, help you check in as safe, plan your packing and budget. Just ask!",
                "Try asking me about taxis, ATMs, food safety, hotels, scams or what to do at night. You can also use the buttons on the home screen."
            )
        ),

        Intent(
            Regex("^(how are you|how are you doing|how r u|are you ok|are you okay)$"),
            listOf(
                "I'm doing great, thank you! How can I help with your trip?",
                "Feeling cheerful and ready to help. Where are you travelling?"
            )
        )
    )

    private val apostrophes = Regex("['’`]")
    private val nonLetters = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
    private val spaces = Regex("\\s+")

    /** Returns a reply, or null if the message is not small talk. */
    fun reply(question: String): String? {
        val text = question
            .lowercase(Locale.ROOT)
            .replace(apostrophes, "")
            .replace(nonLetters, " ")
            .replace(spaces, " ")
            .trim()

        if (text.isEmpty()) return null

        for (intent in intents) {
            if (intent.pattern.matches(text)) return intent.replies.random()
        }
        return null
    }
}
