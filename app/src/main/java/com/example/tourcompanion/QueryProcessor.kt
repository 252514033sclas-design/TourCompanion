package com.example.tourcompanion

import android.content.Context

class QueryProcessor(
    context: Context
) {
    private val databaseHelper = TravelDatabaseHelper(context)

    fun processQuery(question: String): TravelTip? {

        val words = question
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        val db = databaseHelper.readableDatabase

        val cursor = db.rawQuery(
            "SELECT topic, keywords, answer FROM travel_tips",
            null
        )

        var bestResult: TravelTip? = null
        var bestScore = 0

        while (cursor.moveToNext()) {

            val topic = cursor.getString(0)
            val keywordsText = cursor.getString(1)
            val answer = cursor.getString(2)

            val keywords = keywordsText
                .split(",")
                .map { it.trim().lowercase() }

            var score = 0

            for (keyword in keywords) {

                // Exact keyword match gets a strong score
                if (words.contains(keyword)) {
                    score += 10
                }

                // Singular/plural matching
                if (keyword.endsWith("s") &&
                    words.contains(keyword.dropLast(1))
                ) {
                    score += 8
                }

                if (words.any { word ->
                        word.endsWith("s") &&
                                word.dropLast(1) == keyword
                    }
                ) {
                    score += 8
                }
            }

            /*
             * Generic words should not dominate specific topics.
             * For example:
             * "where" + "find" should not beat "ATM".
             */
            val genericWords = setOf(
                "where",
                "what",
                "how",
                "can",
                "is",
                "the",
                "a",
                "an",
                "i",
                "me",
                "my",
                "do",
                "should",
                "tell",
                "please"
            )

            for (word in words) {
                if (word !in genericWords &&
                    keywords.contains(word)
                ) {
                    score += 5
                }
            }

            if (score > bestScore) {
                bestScore = score

                bestResult = TravelTip(
                    topic = topic,
                    keywords = keywords,
                    answer = answer
                )
            }
        }

        cursor.close()

        return bestResult
    }
}