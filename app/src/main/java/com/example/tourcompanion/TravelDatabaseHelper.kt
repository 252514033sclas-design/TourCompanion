package com.example.tourcompanion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class UsefulPhraseSync(
    val id: Long = 0,
    val language: String,
    val category: String,
    val phrase: String,
    val meaning: String,
    val roman: String = ""
)

/**
 * Travel tips and useful phrases are stored in SQLite.
 * Can be refreshed from the MySQL backend or read offline.
 */
class TravelDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "saathi_travel.db", null, DATABASE_VERSION) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE travel_tips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                topic TEXT NOT NULL,
                keywords TEXT NOT NULL,
                answer TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS useful_phrases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                category TEXT NOT NULL,
                phrase TEXT NOT NULL,
                meaning TEXT NOT NULL,
                roman TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        for (category in KnowledgeBase.categories(appContext)) {
            for (tip in category.tips) {
                val values = ContentValues()
                values.put("category", category.id)
                values.put("topic", tip.topic)
                values.put("keywords", tip.keywords)
                values.put("answer", tip.answers.joinToString(ANSWER_SEP))
                db.insert("travel_tips", null, values)
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS travel_tips")
        db.execSQL("DROP TABLE IF EXISTS useful_phrases")
        onCreate(db)
    }

    private fun ensurePhrasesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS useful_phrases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                category TEXT NOT NULL,
                phrase TEXT NOT NULL,
                meaning TEXT NOT NULL,
                roman TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    /**
     * Synchronizes tips from MySQL backend into the local SQLite database.
     * Updates existing topics or inserts new ones, preventing duplicate records.
     */
    fun syncRemoteTips(remoteTips: List<TravelTipSync>): Int {
        if (remoteTips.isEmpty()) return 0
        val db = writableDatabase
        var syncedCount = 0
        db.beginTransaction()
        try {
            for (tip in remoteTips) {
                val cleanTopic = tip.topic.trim()
                if (cleanTopic.isEmpty()) continue

                val cursor = db.rawQuery(
                    "SELECT id FROM travel_tips WHERE LOWER(topic) = LOWER(?) LIMIT 1",
                    arrayOf(cleanTopic)
                )
                val exists = cursor.use { it.moveToFirst() }

                val values = ContentValues().apply {
                    put("topic", cleanTopic)
                    put("keywords", tip.keywords.trim())
                    put("answer", tip.answer.trim())
                }

                if (exists) {
                    db.update("travel_tips", values, "LOWER(topic) = LOWER(?)", arrayOf(cleanTopic))
                } else {
                    values.put("category", "general")
                    db.insert("travel_tips", null, values)
                }
                syncedCount++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return syncedCount
    }

    /**
     * Synchronizes useful phrases from MySQL backend into local SQLite.
     * Prevents duplicates by matching language and phrase.
     */
    fun syncPhrases(remotePhrases: List<UsefulPhraseSync>): Int {
        if (remotePhrases.isEmpty()) return 0
        val db = writableDatabase
        ensurePhrasesTable(db)
        var syncedCount = 0
        db.beginTransaction()
        try {
            for (p in remotePhrases) {
                val cleanLang = p.language.trim()
                val cleanPhrase = p.phrase.trim()
                val cleanMeaning = p.meaning.trim()
                val cleanCategory = p.category.trim()
                if (cleanPhrase.isEmpty() || cleanLang.isEmpty()) continue

                val cursor = db.rawQuery(
                    "SELECT id FROM useful_phrases WHERE LOWER(language) = LOWER(?) AND LOWER(phrase) = LOWER(?) LIMIT 1",
                    arrayOf(cleanLang, cleanPhrase)
                )
                val exists = cursor.use { it.moveToFirst() }

                val values = ContentValues().apply {
                    put("language", cleanLang)
                    put("category", cleanCategory)
                    put("phrase", cleanPhrase)
                    put("meaning", cleanMeaning)
                    put("roman", p.roman)
                }

                if (exists) {
                    db.update(
                        "useful_phrases",
                        values,
                        "LOWER(language) = LOWER(?) AND LOWER(phrase) = LOWER(?)",
                        arrayOf(cleanLang, cleanPhrase)
                    )
                } else {
                    db.insert("useful_phrases", null, values)
                }
                syncedCount++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return syncedCount
    }

    /**
     * Loads phrases for a specific language from local SQLite cache.
     */
    fun getPhrasesByLanguage(language: String): List<UsefulPhraseSync> {
        val db = readableDatabase
        ensurePhrasesTable(db)
        val list = ArrayList<UsefulPhraseSync>()
        val cursor = db.rawQuery(
            "SELECT id, language, category, phrase, meaning, roman FROM useful_phrases WHERE LOWER(language) = LOWER(?) ORDER BY id",
            arrayOf(language.trim())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    UsefulPhraseSync(
                        id = it.getLong(0),
                        language = it.getString(1),
                        category = it.getString(2),
                        phrase = it.getString(3),
                        meaning = it.getString(4),
                        roman = it.getString(5)
                    )
                )
            }
        }
        return list
    }

    companion object {
        const val DATABASE_VERSION = 5

        /** Several answers for one tip are stored in one column, separated by this character. */
        const val ANSWER_SEP = "\u001F"
    }
}
