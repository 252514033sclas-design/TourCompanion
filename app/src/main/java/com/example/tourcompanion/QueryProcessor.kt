package com.example.tourcompanion

import android.content.Context

/**
 * Loads the travel tips from the SQLite database and answers questions with TipMatcher.
 * Public API unchanged: processQuery(question): TravelTip?
 *
 * Call warmUp() from a background thread (the splash screen does) so the first
 * question never does database work on the main thread.
 */
class QueryProcessor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var matcher: TipMatcher? = null

    private fun getMatcher(): TipMatcher {
        return matcher ?: synchronized(this) {
            matcher ?: TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP).also { matcher = it }
        }
    }

    fun warmUp() {
        check(getMatcher().size > 0) { "travel_tips table is empty" }
    }

    fun reload() {
        synchronized(this) {
            matcher = TipMatcher(loadTips(), TravelDatabaseHelper.ANSWER_SEP)
        }
    }

    fun processQuery(question: String): TravelTip? = getMatcher().match(question)

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
