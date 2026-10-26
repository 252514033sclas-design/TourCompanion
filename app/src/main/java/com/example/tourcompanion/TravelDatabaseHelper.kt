package com.example.tourcompanion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Travel tips are stored in res/raw/tips.json (easy to edit) and copied into SQLite
 * the first time the app runs. Bump DATABASE_VERSION after editing tips.json so the
 * table is rebuilt on devices that already have the app.
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
        onCreate(db)
    }

    companion object {
        const val DATABASE_VERSION = 5

        /** Several answers for one tip are stored in one column, separated by this character. */
        const val ANSWER_SEP = "\u001F"
    }
}
