package com.example.tourcompanion

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.CheckBox

class PackingActivity : ListScreenActivity() {

    private val packed = HashSet<String>()
    private var totalItems = 0

    override fun screenTitle() = getString(R.string.packing_title)

    override fun buildContent() {
        packed.addAll(Prefs.packed(this))

        for (group in KnowledgeBase.packing(this)) {
            UiKit.addSectionHeader(container, group.title)

            for (item in group.items) {
                totalItems++
                val key = "${group.title}|$item"

                val box = CheckBox(this)
                box.text = item
                box.textSize = 16f
                box.setTextColor(Color.parseColor("#0F172A"))
                box.buttonTintList = ColorStateList.valueOf(Color.parseColor("#0F766E"))
                box.setPadding(16, 20, 16, 20)
                box.isChecked = key in packed
                box.setOnCheckedChangeListener { _, checked ->
                    if (checked) packed.add(key) else packed.remove(key)
                    Prefs.setPacked(this, packed)
                    updateProgress()
                }
                container.addView(box)
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        subtitleView.text = getString(R.string.packing_progress, packed.size.coerceAtMost(totalItems), totalItems)
    }
}
