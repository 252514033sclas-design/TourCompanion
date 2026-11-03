package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SafetyCheckInActivity : AppCompatActivity() {

    private lateinit var safetyStatusText: TextView
    private lateinit var imSafeButton: Button
    private lateinit var needHelpButton: Button
    private lateinit var shareSafeButton: Button
    private lateinit var backHomeButton: Button
    private lateinit var saathiView: SaathiView
    private lateinit var speaker: Speaker

    private lateinit var safetyRepository: SafetyRepository
    private var currentStatus: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_safety_checkin)
        EdgeToEdge.apply(this)

        safetyRepository = AppServices.safetyRepository(this)

        safetyStatusText = findViewById(R.id.safetyStatusText)
        imSafeButton = findViewById(R.id.imSafeButton)
        needHelpButton = findViewById(R.id.needHelpButton)
        shareSafeButton = findViewById(R.id.shareSafeButton)
        backHomeButton = findViewById(R.id.backHomeButton)
        saathiView = findViewById(R.id.saathiViewSafety)

        speaker = Speaker(this, saathiView)

        loadLatestStatus()

        imSafeButton.setOnClickListener {
            performCheckIn("safe")
        }

        needHelpButton.setOnClickListener {
            performCheckIn("need_help")
        }

        shareSafeButton.setOnClickListener {
            val message = if (currentStatus == "need_help") {
                "⚠️ I need help! Please contact or check on me. (Sent from TourCompanion)"
            } else {
                getString(R.string.safe_share_message)
            }

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

    override fun onResume() {
        super.onResume()
        val userId = Session.userId(this)
        if (userId > 0L) {
            lifecycleScope.launch {
                val synced = safetyRepository.syncPendingCheckIns(userId)
                if (synced > 0) {
                    loadLatestStatus()
                }
            }
        }
    }

    private fun loadLatestStatus() {
        val userId = Session.userId(this)
        val isGuest = Session.isGuest(this) || userId <= 0L

        if (isGuest) {
            lifecycleScope.launch {
                val result = safetyRepository.getLatestStatus(userId)
                displayStatusResult(result)
            }
            return
        }

        safetyStatusText.text = "Checking latest status…"
        lifecycleScope.launch {
            val result = safetyRepository.getLatestStatus(userId)
            displayStatusResult(result)
        }
    }

    private fun displayStatusResult(result: SafetyStatusResult) {
        if (result.status.isEmpty()) {
            currentStatus = ""
            if (result.isGuest) {
                safetyStatusText.text = "Guest Mode\nCheck-ins are saved locally on this device.\nTap below to check in."
            } else {
                safetyStatusText.text = "You have not checked in yet.\nTap below to let Semmozhi know you are safe."
            }
            shareSafeButton.visibility = View.GONE
            return
        }

        currentStatus = result.status
        val timeDisplay = if (result.timestamp.isNotEmpty()) "at ${result.timestamp}" else ""

        if (result.status == "safe") {
            val syncNote = when {
                result.isGuest -> "(Guest Mode - Local)"
                result.isSynced -> "(Synced with server)"
                result.isOffline -> "(Offline - Cached)"
                else -> "(Saved locally)"
            }
            safetyStatusText.text = "✓ Status: Safe\nChecked in $timeDisplay\n$syncNote"
            shareSafeButton.text = getString(R.string.tell_someone)
            shareSafeButton.visibility = View.VISIBLE
        } else if (result.status == "need_help") {
            val syncNote = when {
                result.isGuest -> "(Guest Mode - Local)"
                result.isSynced -> "(Alert recorded on server)"
                result.isOffline -> "(Offline - Cached)"
                else -> "(Saved locally)"
            }
            safetyStatusText.text = "⚠️ Status: Need Help\nRequested $timeDisplay\n$syncNote"
            shareSafeButton.text = "⚠️ Alert Emergency Contact"
            shareSafeButton.visibility = View.VISIBLE
        }
    }

    private fun performCheckIn(status: String) {
        val userId = Session.userId(this)
        imSafeButton.isEnabled = false
        needHelpButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = safetyRepository.recordCheckIn(userId, status)
                currentStatus = status

                if (status == "safe") {
                    val syncNote = when {
                        result.isGuest -> "(Saved locally - Guest Mode)"
                        result.isSynced -> "(Synced with server)"
                        else -> "(Offline: Saved locally, will sync when online)"
                    }
                    safetyStatusText.text = "✓ Marked Safe\n$syncNote\nWould you like to tell someone you trust?"
                    shareSafeButton.text = getString(R.string.tell_someone)
                    shareSafeButton.visibility = View.VISIBLE
                    speaker.speak(getString(R.string.safe_spoken))
                } else {
                    val syncNote = when {
                        result.isGuest -> "(Saved locally - Guest Mode)"
                        result.isSynced -> "(Alert recorded on server)"
                        else -> "(Offline: Saved locally, will sync when online)"
                    }
                    safetyStatusText.text = "⚠️ Need Help Recorded\n$syncNote\nPlease contact emergency services or a trusted contact."
                    shareSafeButton.text = "⚠️ Alert Emergency Contact"
                    shareSafeButton.visibility = View.VISIBLE
                    speaker.speak("Help requested. Please reach out to your emergency contact or authorities.")
                }
            } finally {
                imSafeButton.isEnabled = true
                needHelpButton.isEnabled = true
            }
        }
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
