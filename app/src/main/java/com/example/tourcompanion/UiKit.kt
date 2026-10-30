package com.example.tourcompanion

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Builds the repeated cards and rows used by the list-style screens. */
object UiKit {

    /** Soft, friendly colours for the icon circles (the same title always gets the same colour). */
    private val PALETTE = listOf(
        "#0D9488", "#0284C7", "#7C3AED", "#D97706", "#059669", "#DB2777", "#4F46E5", "#EA580C"
    )

    private fun colorFor(text: String): Int =
        Color.parseColor(PALETTE[Math.abs(text.hashCode()) % PALETTE.size])

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
            // Short text badges such as "SOS" need a smaller, bold font to fit the icon circle.
            iconView.textSize = 13f
            iconView.setTypeface(null, Typeface.BOLD)
        }

        val titleView = row.findViewById<TextView>(R.id.rowTitle)
        titleView.text = title

        val sub = row.findViewById<TextView>(R.id.rowSubtitle)
        if (subtitle.isNullOrBlank()) sub.visibility = View.GONE else sub.text = subtitle

        // A coloured circle with a soft tint behind the icon; red titles for dangerous actions.
        val color = if (accentColor != null) Color.parseColor(accentColor) else colorFor(title)
        iconView.setTextColor(color)
        iconView.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 40))
        if (accentColor != null) titleView.setTextColor(color)

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
        header.text = text.uppercase()
        header.textSize = 13f
        header.letterSpacing = 0.08f
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        header.setTextColor(Color.parseColor("#0F766E"))
        header.setPadding(dp(container, 6), dp(container, 18), 0, dp(container, 4))
        container.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
}
