package com.example.tourcompanion

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the repeated cards and rows used by the list-style screens. */
object UiKit {

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    fun addMenuRow(
        container: LinearLayout,
        icon: String,
        title: String,
        subtitle: String? = null,
        accentColor: String? = null,
        onClick: () -> Unit
    ) {
        val row = LayoutInflater.from(container.context).inflate(R.layout.item_menu_row, container, false)
        val iconView = row.findViewById<TextView>(R.id.rowIcon)
        iconView.text = icon
        if (icon.length > 2) {
            // Short text badges such as "SOS" need a smaller, bold font to fit the icon box.
            iconView.textSize = 13f
            iconView.setTypeface(null, Typeface.BOLD)
        }
        row.findViewById<TextView>(R.id.rowTitle).text = title

        val sub = row.findViewById<TextView>(R.id.rowSubtitle)
        if (subtitle.isNullOrBlank()) sub.visibility = View.GONE else sub.text = subtitle

        if (accentColor != null) {
            val color = Color.parseColor(accentColor)
            row.findViewById<TextView>(R.id.rowIcon).setTextColor(color)
            row.findViewById<TextView>(R.id.rowTitle).setTextColor(color)
        }

        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    fun addInfoCard(container: LinearLayout, title: String, body: String, onListen: () -> Unit) {
        val card = LayoutInflater.from(container.context).inflate(R.layout.item_info_card, container, false)
        card.findViewById<TextView>(R.id.cardTitle).text = title
        card.findViewById<TextView>(R.id.cardBody).text = body
        card.findViewById<View>(R.id.cardListen).setOnClickListener { onListen() }
        container.addView(card)
    }

    fun addPhraseCard(
        container: LinearLayout,
        english: String,
        native: String,
        roman: String,
        onListen: () -> Unit
    ) {
        val card = LayoutInflater.from(container.context).inflate(R.layout.item_phrase_card, container, false)
        card.findViewById<TextView>(R.id.phraseEnglish).text = english
        card.findViewById<TextView>(R.id.phraseNative).text = native
        card.findViewById<TextView>(R.id.phraseRoman).text = roman
        card.findViewById<View>(R.id.phraseListen).setOnClickListener { onListen() }
        container.addView(card)
    }

    fun addSectionHeader(container: LinearLayout, text: String) {
        val header = TextView(container.context)
        header.text = text
        header.textSize = 18f
        header.setTypeface(null, Typeface.BOLD)
        header.setTextColor(Color.parseColor("#0F766E"))
        header.setPadding(dp(container, 4), dp(container, 14), 0, dp(container, 4))
        container.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
}
