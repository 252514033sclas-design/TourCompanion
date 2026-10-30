package com.example.tourcompanion

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Template for every list-style screen (tips, phrases, helplines, packing ...).
 * A subclass only provides a title and fills the container with rows or cards.
 */
abstract class ListScreenActivity : AppCompatActivity() {

    protected lateinit var container: LinearLayout
    protected lateinit var avatar: SaathiView
    protected lateinit var subtitleView: TextView

    private var speakerInstance: Speaker? = null

    /** Created the first time something needs to be spoken. */
    protected val speaker: Speaker
        get() = speakerInstance ?: Speaker(this, avatar).also { speakerInstance = it }

    protected abstract fun screenTitle(): String
    protected open fun screenSubtitle(): String = ""
    protected abstract fun buildContent()

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_screen_list)
        EdgeToEdge.apply(this)

        container = findViewById(R.id.listContainer)
        avatar = findViewById(R.id.screenAvatar)
        subtitleView = findViewById(R.id.screenSubtitle)

        findViewById<TextView>(R.id.screenTitle).text = screenTitle()
        subtitleView.text = screenSubtitle()
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        buildContent()
    }

    override fun onStop() {
        speakerInstance?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speakerInstance?.shutdown()
        super.onDestroy()
    }
}
