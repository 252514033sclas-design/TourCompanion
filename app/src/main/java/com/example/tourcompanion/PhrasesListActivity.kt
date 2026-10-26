package com.example.tourcompanion

import android.widget.Toast
import java.util.Locale

/** One screen for both Tamil and Hindi phrase lists. */
class PhrasesListActivity : ListScreenActivity() {

    private val phraseSet: PhraseSet? by lazy {
        KnowledgeBase.phrases(this, intent.getStringExtra(EXTRA_LANGUAGE) ?: "tamil")
    }

    override fun screenTitle() = phraseSet?.let { getString(R.string.phrases_for, it.title) }
        ?: getString(R.string.phrases_title)

    override fun screenSubtitle() = getString(R.string.phrases_listen_hint)

    override fun buildContent() {
        val set = phraseSet ?: return
        val locale = Locale.forLanguageTag(set.localeTag)

        for (phrase in set.phrases) {
            UiKit.addPhraseCard(container, phrase.english, phrase.native, phrase.roman) {
                if (!speaker.speak(phrase.native, locale)) {
                    // No voice for this language: read the English spelling instead and explain.
                    Toast.makeText(
                        this,
                        getString(R.string.voice_language_missing, set.title),
                        Toast.LENGTH_LONG
                    ).show()
                    speaker.speak(phrase.roman, Locale.ENGLISH)
                }
            }
        }
    }

    companion object {
        const val EXTRA_LANGUAGE = "language"
    }
}
