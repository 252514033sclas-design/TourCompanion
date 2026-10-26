package com.example.tourcompanion

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_settings)

        speaker = Speaker(this, findViewById(R.id.settingsAvatar))

        val voiceSwitch = findViewById<SwitchCompat>(R.id.voiceSwitch)
        voiceSwitch.isChecked = Prefs.voiceEnabled(this)
        voiceSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setVoiceEnabled(this, checked) }

        val rateGroup = findViewById<RadioGroup>(R.id.rateGroup)
        val rate = Prefs.speechRate(this)
        rateGroup.check(
            when {
                rate < 0.9f -> R.id.rateSlow
                rate > 1.05f -> R.id.rateFast
                else -> R.id.rateNormal
            }
        )
        rateGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rateSlow -> 0.8f
                R.id.rateFast -> 1.15f
                else -> 0.95f
            }
            Prefs.setSpeechRate(this, value)
        }

        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            if (!Prefs.voiceEnabled(this)) {
                Toast.makeText(this, R.string.voice_is_off, Toast.LENGTH_SHORT).show()
            } else {
                speaker.speak(getString(R.string.settings_test_phrase))
            }
        }

        findViewById<Button>(R.id.clearDataButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_title)
                .setMessage(R.string.clear_message)
                .setPositiveButton(R.string.clear_confirm) { _, _ ->
                    Prefs.clearAll(this)
                    Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        findViewById<Button>(R.id.backSettingsButton).setOnClickListener { finish() }
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
