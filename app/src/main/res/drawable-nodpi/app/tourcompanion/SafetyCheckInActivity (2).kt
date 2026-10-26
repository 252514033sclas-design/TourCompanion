package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SafetyCheckInActivity : AppCompatActivity() {

    private lateinit var safetyStatusText: TextView
    private lateinit var imSafeButton: Button
    private lateinit var backHomeButton: Button
    private lateinit var saathiView: SaathiView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        setContentView(R.layout.activity_safety_checkin)

        safetyStatusText = findViewById(R.id.safetyStatusText)
        imSafeButton = findViewById(R.id.imSafeButton)
        backHomeButton = findViewById(R.id.backHomeButton)
        saathiView = findViewById(R.id.saathiViewSafety)

        imSafeButton.setOnClickListener {

            safetyStatusText.text =
                "✓ You're marked as safe.\nSemmozhi is glad you're okay!"

            saathiView.setSpeaking(true)

            saathiView.postDelayed({
                saathiView.setSpeaking(false)
            }, 2500)
        }

        backHomeButton.setOnClickListener {

            val intent = Intent(
                this,
                MainActivity::class.java
            )

            startActivity(intent)

            finish()
        }
    }
}