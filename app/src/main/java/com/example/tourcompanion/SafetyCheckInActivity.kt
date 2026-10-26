package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SafetyCheckInActivity : AppCompatActivity() {

    private lateinit var safetyStatusText: TextView
    private lateinit var imSafeButton: Button
    private lateinit var shareSafeButton: Button
    private lateinit var backHomeButton: Button
    private lateinit var saathiView: SaathiView
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_safety_checkin)

        safetyStatusText = findViewById(R.id.safetyStatusText)
        imSafeButton = findViewById(R.id.imSafeButton)
        shareSafeButton = findViewById(R.id.shareSafeButton)
        backHomeButton = findViewById(R.id.backHomeButton)
        saathiView = findViewById(R.id.saathiViewSafety)

        speaker = Speaker(this, saathiView)

        imSafeButton.setOnClickListener {
            safetyStatusText.text = getString(R.string.safe_marked)
            shareSafeButton.visibility = View.VISIBLE
            speaker.speak(getString(R.string.safe_spoken))
        }

        // Nothing is sent automatically. With a saved contact the SMS app opens ready to send;
        // otherwise the user chooses who to tell (SMS, WhatsApp, ...).
        shareSafeButton.setOnClickListener {
            val message = getString(R.string.safe_share_message)
            val contact = Prefs.contactPhone(this)

            if (contact.isNotBlank()) {
                ContactActions.sms(this, contact, message)
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                startActivity(Intent.createChooser(send, getString(R.string.safe_share_title)))
            }
        }

        backHomeButton.setOnClickListener { finish() }
    }

    override fun onStop() {
        speaker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
