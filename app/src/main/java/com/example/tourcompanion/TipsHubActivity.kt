package com.example.tourcompanion

import android.content.Intent

class TipsHubActivity : ListScreenActivity() {

    override fun screenTitle() = getString(R.string.tips_title)
    override fun screenSubtitle() = getString(R.string.tips_subtitle)

    override fun buildContent() {
        for (category in KnowledgeBase.categories(this)) {
            UiKit.addMenuRow(
                container,
                category.icon,
                category.title,
                getString(R.string.tips_count, category.tips.size)
            ) {
                startActivity(
                    Intent(this, TipsCategoryActivity::class.java)
                        .putExtra(TipsCategoryActivity.EXTRA_CATEGORY, category.id)
                )
            }
        }
    }
}
