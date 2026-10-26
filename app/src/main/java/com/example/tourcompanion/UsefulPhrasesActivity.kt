package com.example.tourcompanion

import android.content.Intent

/** Phrase book hub: choose Tamil or Hindi. */
class UsefulPhrasesActivity : ListScreenActivity() {

    override fun screenTitle() = getString(R.string.phrases_title)
    override fun screenSubtitle() = getString(R.string.phrases_subtitle)

    override fun buildContent() {
        UiKit.addMenuRow(container, "த", getString(R.string.tamil_phrases), getString(R.string.phrases_count)) {
            open("tamil")
        }
        UiKit.addMenuRow(container, "हि", getString(R.string.hindi_phrases), getString(R.string.phrases_count)) {
            open("hindi")
        }
    }

    private fun open(language: String) {
        startActivity(
            Intent(this, PhrasesListActivity::class.java)
                .putExtra(PhrasesListActivity.EXTRA_LANGUAGE, language)
        )
    }
}
