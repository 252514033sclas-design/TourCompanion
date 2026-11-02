package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsefulPhrasesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = UsefulPhrasesApiClient()
    private val dbHelper = TravelDatabaseHelper(appContext)

    /**
     * Downloads useful phrases from MySQL backend, enriches roman transliterations,
     * and saves to SQLite preventing duplicates.
     */
    suspend fun syncPhrases(): Boolean = withContext(Dispatchers.IO) {
        try {
            val remotePhrases = api.fetchUsefulPhrases()
            if (remotePhrases.isNullOrEmpty()) {
                Log.d("UsefulPhrasesRepo", "No remote phrases received (offline). Using local cache.")
                return@withContext false
            }

            val enriched = remotePhrases.map { p ->
                val roman = if (p.roman.isNotBlank()) {
                    p.roman
                } else {
                    val langKey = p.language.lowercase()
                    val kb = KnowledgeBase.phrases(appContext, langKey)
                    kb?.phrases?.firstOrNull {
                        it.native.equals(p.phrase, ignoreCase = true) ||
                        it.english.equals(p.meaning, ignoreCase = true)
                    }?.roman ?: ""
                }
                p.copy(roman = roman)
            }

            val count = dbHelper.syncPhrases(enriched)
            Log.d("UsefulPhrasesRepo", "Synced $count useful phrases into SQLite.")
            true
        } catch (e: Exception) {
            Log.w("UsefulPhrasesRepo", "Sync failed: ${e.message}. Using cached phrases.")
            false
        }
    }

    /**
     * Returns phrases for the given language.
     * 1. Checks local SQLite cache.
     * 2. If SQLite has no records yet, falls back to KnowledgeBase.phrases.
     */
    suspend fun getPhrases(language: String): List<Phrase> = withContext(Dispatchers.IO) {
        val langKey = language.lowercase()
        val cached = dbHelper.getPhrasesByLanguage(langKey)

        if (cached.isNotEmpty()) {
            return@withContext cached.map { c ->
                if (langKey == "english") {
                    Phrase(
                        english = c.phrase,
                        native = c.phrase,
                        roman = c.meaning
                    )
                } else {
                    Phrase(
                        english = c.meaning,
                        native = c.phrase,
                        roman = c.roman.ifBlank { c.meaning }
                    )
                }
            }
        }

        // Fallback to KnowledgeBase if SQLite cache is empty
        val fallback = KnowledgeBase.phrases(appContext, langKey)
        fallback?.phrases ?: emptyList()
    }
}

