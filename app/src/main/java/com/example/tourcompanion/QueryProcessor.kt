package com.example.tourcompanion

import android.content.Context

/**
 * Loads the travel tips from the database once and answers questions with TipMatcher.
 * Public API unchanged: processQuery(question): TravelTip?
 *
 * Call warmUp() from a background thread (the splash screen does) so the first
 * question never does database work on the main thread.
 */
class QueryProcessor(context: Context) {

    private val appContext = context.applicationContext

    private val matcher: TipMatcher by lazy { TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP) }

    fun warmUp() {
        check(matcher.size > 0) { "travel_tips table is empty" }
    }

    fun processQuery(question: String): TravelTip? = matcher.match(question)

    private fun loadTips(): List<TravelTip> {

        val helper = TravelDatabaseHelper(appContext)
        val result = ArrayList<TravelTip>()

        try {
            helper.readableDatabase
                .rawQuery("SELECT topic, keywords, answer FROM travel_tips ORDER BY id", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        result += TravelTip(
                            topic = cursor.getString(0),
                            keywords = cursor.getString(1)
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            answer = cursor.getString(2)
                        )
                    }
                }
        } finally {
            helper.close()
        }

        return result
    }
}
