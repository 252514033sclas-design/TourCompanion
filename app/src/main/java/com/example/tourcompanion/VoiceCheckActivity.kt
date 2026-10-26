package com.example.tourcompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Diagnoses the two halves of voice:
 *   - speaking (text-to-speech engine + English / Tamil / Hindi voices)
 *   - listening (microphone permission + a speech recogniser on the phone)
 * and lets you test both with one tap.
 */
class VoiceCheckActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refresh()
            if (granted) startListening() else toast(R.string.mic_permission_needed)
        }

    private val listenLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()

            heardText.text = if (result.resultCode == RESULT_OK && !heard.isNullOrBlank()) {
                getString(R.string.voice_heard, heard)
            } else {
                getString(R.string.voice_heard_nothing)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_voice_check)

        statusText = findViewById(R.id.voiceStatus)
        heardText = findViewById(R.id.heardText)

        speaker = Speaker(this, findViewById(R.id.voiceAvatar))
        speaker.onStateChanged = { refresh() }

        findViewById<Button>(R.id.testSpeakButton).setOnClickListener {
            if (!Prefs.voiceEnabled(this)) {
                toast(R.string.voice_is_off)
            } else if (!speaker.speak(getString(R.string.voice_test_phrase))) {
                toast(R.string.voice_engine_problem)
            }
        }

        findViewById<Button>(R.id.testMicButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        findViewById<Button>(R.id.voiceSettingsButton).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        findViewById<Button>(R.id.installVoiceButton).setOnClickListener {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: ActivityNotFoundException) {
                toast(R.string.voice_install_unavailable)
            }
        }

        findViewById<Button>(R.id.backVoiceButton).setOnClickListener { finish() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // after coming back from the phone's voice settings
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            listenLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.voice_unavailable)
        }
    }

    private fun refresh() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val lines = ArrayList<String>()

        lines += when {
            speaker.isReady -> "✅ Speech engine ready (${speaker.engineName})"
            speaker.hasFailed -> "❌ Speech engine not available or English voice data missing"
            else -> "⏳ Starting speech engine…"
        }
        lines += languageLine("English voice", Locale.ENGLISH)
        lines += languageLine("Tamil voice", Locale.forLanguageTag("ta-IN"))
        lines += languageLine("Hindi voice", Locale.forLanguageTag("hi-IN"))
        lines += if (micGranted) "✅ Microphone permission granted" else "⚠️ Microphone permission not granted yet"
        lines += if (SpeechRecognizer.isRecognitionAvailable(this)) {
            "✅ Speech recognition available"
        } else {
            "❌ Speech recognition not available (install or enable the Google app / a voice service)"
        }
        lines += if (Prefs.voiceEnabled(this)) "✅ Voice is ON in Settings" else "⚠️ Voice is OFF in Settings"

        statusText.text = lines.joinToString("\n")
    }

    private fun languageLine(label: String, locale: Locale): String = when {
        !speaker.isReady -> "⏳ $label: checking…"
        speaker.isLanguageAvailable(locale) -> "✅ $label installed"
        else -> "⚠️ $label not installed (use Install voice data)"
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    override fun onStop() {
        speaker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
