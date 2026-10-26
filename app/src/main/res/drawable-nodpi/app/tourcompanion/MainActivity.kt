package com.example.tourcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var saathiView: SaathiView
    private lateinit var questionInput: EditText
    private lateinit var answerText: TextView

    private lateinit var askButton: Button
    private lateinit var voiceButton: Button
    private lateinit var safetyButton: View
    private lateinit var usefulPhrasesButton: View
    private lateinit var emergencyButton: View

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var queryProcessor: QueryProcessor

    private val handler = Handler(Looper.getMainLooper())

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val voiceResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val results =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                if (!results.isNullOrEmpty()) {
                    val question = results[0]
                    questionInput.setText(question)
                    processQuestion(question)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        saathiView = findViewById(R.id.saathiView)
        questionInput = findViewById(R.id.questionInput)
        answerText = findViewById(R.id.answerText)

        askButton = findViewById(R.id.askButton)
        voiceButton = findViewById(R.id.voiceButton)
        safetyButton = findViewById<View>(R.id.safetyButton)
        usefulPhrasesButton =
            findViewById<View>(R.id.usefulPhrasesButton)
        emergencyButton =
            findViewById<View>(R.id.emergencyButton)

        queryProcessor = QueryProcessor(this)
        textToSpeech = TextToSpeech(this, this)

        // Ask Semmozhi
        askButton.setOnClickListener {
            val intent = Intent(
                this,
                AskSemmozhiActivity::class.java
            )
            startActivity(intent)
        }

        // Voice input
        voiceButton.setOnClickListener {
            if (
                checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                microphonePermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            } else {
                startVoiceInput()
            }
        }

        // Safety Check-in
        safetyButton.setOnClickListener {
            val intent = Intent(
                this,
                SafetyCheckInActivity::class.java
            )
            startActivity(intent)
        }

        // Useful Phrases
        usefulPhrasesButton.setOnClickListener {
            val intent = Intent(
                this,
                UsefulPhrasesActivity::class.java
            )
            startActivity(intent)
        }

        // Emergency 112
        emergencyButton.setOnClickListener {
            val intent = Intent(
                this,
                EmergencyActivity::class.java
            )
            startActivity(intent)
        }
    }

    private fun processQuestion(question: String) {
        val result =
            queryProcessor.processQuery(question)

        if (result != null) {
            answerText.text = result.answer
            speakAnswer(result.answer)
        } else {
            val answer =
                "I could not find a matching travel tip. Try asking about safety, transport, money, phrases, emergencies, or night travel."

            answerText.text = answer
            speakAnswer(answer)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.ENGLISH
            textToSpeech.setSpeechRate(0.95f)
        } else {
            Toast.makeText(
                this,
                "Text-to-speech could not be started.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun speakAnswer(answer: String) {
        if (!::textToSpeech.isInitialized) {
            return
        }

        saathiView.setSpeaking(true)

        textToSpeech.stop()

        textToSpeech.speak(
            answer,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "travel_answer"
        )

        val duration =
            (answer.length * 55L)
                .coerceIn(1500L, 9000L)

        handler.removeCallbacksAndMessages(null)

        handler.postDelayed({
            saathiView.setSpeaking(false)
        }, duration)
    }

    private fun startVoiceInput() {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            Locale.getDefault()
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "Ask me about your journey"
        )

        voiceResultLauncher.launch(intent)
    }

    private fun openEmergencyDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:112")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to open the emergency dialer.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        super.onDestroy()
    }
}