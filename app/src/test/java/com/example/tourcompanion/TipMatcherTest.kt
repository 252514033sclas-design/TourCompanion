package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests: no phone needed. Run with Android Studio's green arrow next to the class. */
class TipMatcherTest {

    private fun tip(topic: String, keywords: String, vararg answers: String) =
        TravelTip(topic, keywords.split(",").map { it.trim() }, answers.joinToString("\u001F"))

    private val matcher = TipMatcher(
        listOf(
            tip("Emergency", "emergency,help,police,danger,112,உதவி", "Call 112."),
            tip("Taxi", "taxi,cab,driver,booking", "Use a trusted taxi."),
            tip("ATM Safety", "atm,withdraw,withdrawal,bank", "Use well-lit ATMs."),
            tip("Money", "money,cash,atm,card,payment", "Carry some cash."),
            tip("Lost Wallet", "wallet,lost wallet,missing wallet", "Block your cards."),
            tip("Health", "health,sick,ill,doctor,medicine", "See a doctor.", "Rest and drink water.")
        )
    )

    @Test
    fun helpAloneReturnsEmergency() {
        assertEquals("Emergency", matcher.match("help")?.topic)
    }

    @Test
    fun distressWordsReturnEmergency() {
        assertEquals("Emergency", matcher.match("I'm being attacked")?.topic)
        assertEquals("Emergency", matcher.match("someone is following me")?.topic)
    }

    @Test
    fun helpInALongerQuestionDoesNotTriggerEmergency() {
        assertEquals("Taxi", matcher.match("Can you help me find a taxi")?.topic)
    }

    @Test
    fun topicNameBreaksTiesBetweenSimilarTips() {
        assertEquals("ATM Safety", matcher.match("where is the ATM")?.topic)
    }

    @Test
    fun multiWordKeywordsMatch() {
        assertEquals("Lost Wallet", matcher.match("I lost my wallet")?.topic)
    }

    @Test
    fun unknownQuestionsReturnNull() {
        assertNull(matcher.match("tell me a joke"))
    }

    @Test
    fun tamilScriptKeywordsMatch() {
        assertEquals("Emergency", matcher.match("உதவி")?.topic)
    }

    @Test
    fun oneOfSeveralAnswersIsReturned() {
        val answer = matcher.match("I need a doctor")?.answer
        assertTrue(answer == "See a doctor." || answer == "Rest and drink water.")
    }

    @Test
    fun smallTalkReplies() {
        assertNotNull(SmallTalk.reply("hello"))
        assertNotNull(SmallTalk.reply("Good morning!"))
        assertNotNull(SmallTalk.reply("Thank you"))
        assertNotNull(SmallTalk.reply("who are you"))
        assertNull(SmallTalk.reply("good morning walk tips"))
        assertNull(SmallTalk.reply("help"))
    }
}
