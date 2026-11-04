package com.example.tourcompanion

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Screen for Tamil, Hindi and English useful phrases. */
class PhrasesListActivity : ListScreenActivity() {

    private val language: String by lazy {
        intent.getStringExtra(EXTRA_LANGUAGE)?.lowercase() ?: "tamil"
    }

    override fun screenTitle(): String {
        return when (language) {
            "tamil" -> getString(R.string.phrases_for, "Tamil")
            "hindi" -> getString(R.string.phrases_for, "Hindi")
            "english" -> getString(R.string.phrases_for, "English")
            else -> getString(R.string.phrases_title)
        }
    }

    override fun screenSubtitle() = getString(R.string.phrases_listen_hint)

    override fun buildContent() {
        val locale = when (language) {
            "tamil" -> Locale.forLanguageTag("ta-IN")
            "hindi" -> Locale.forLanguageTag("hi-IN")
            else -> Locale.forLanguageTag("en-IN")
        }

        lifecycleScope.launch {
            val repo = AppServices.usefulPhrasesRepository(applicationContext)

            // 1. Immediately show cached / fallback phrases offline
            val initial = repo.getPhrases(language)
            displayPhrases(initial, locale)

            // 2. Download latest phrases from MySQL backend if online
            val synced = repo.syncPhrases()
            if (synced) {
                val updated = repo.getPhrases(language)
                container.removeAllViews()
                displayPhrases(updated, locale)
            }
        }
    }

    private fun displayPhrases(phrases: List<Phrase>, locale: Locale) {
        val titleText = screenTitle()
        for (phrase in phrases) {
            UiKit.addPhraseCard(container, phrase.english, phrase.native, phrase.roman) {
                if (!speaker.speak(phrase.native, locale)) {
                    Toast.makeText(
                        this,
                        getString(R.string.voice_language_missing, titleText),
                        Toast.LENGTH_LONG
                    ).show()
                    speaker.speak(phrase.roman, Locale.forLanguageTag("en-IN"))
                }
            }
        }
    }

    companion object {
        const val EXTRA_LANGUAGE = "language"
    }
}
