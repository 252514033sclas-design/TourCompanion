package com.example.tourcompanion

class TipsCategoryActivity : ListScreenActivity() {

    private val category: TipCategory? by lazy {
        KnowledgeBase.category(this, intent.getStringExtra(EXTRA_CATEGORY))
    }

    override fun screenTitle() = category?.let { "${it.icon} ${it.title}" } ?: getString(R.string.tips_title)
    override fun screenSubtitle() = getString(R.string.tips_listen_hint)

    override fun buildContent() {
        val current = category
        if (current == null) {
            UiKit.addInfoCard(container, getString(R.string.nothing_here), getString(R.string.nothing_here_body)) {}
            return
        }
        for (tip in current.tips) {
            val answer = tip.answers.first()
            UiKit.addInfoCard(container, tip.topic, answer) { speaker.speak(answer) }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "category"
    }
}
