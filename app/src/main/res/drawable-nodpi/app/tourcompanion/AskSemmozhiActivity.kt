package com.example.tourcompanion

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class AskSemmozhiActivity : AppCompatActivity(),
    TextToSpeech.OnInitListener {

    private lateinit var questionInput: EditText
    private lateinit var askButton: Button
    private lateinit var answerText: TextView
    private lateinit var saathiView: SaathiView

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var queryProcessor: QueryProcessor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        setContentView(R.layout.activity_ask_semmozhi)

        questionInput = findViewById(R.id.askQuestionInput)
        askButton = findViewById(R.id.askSemmozhiButton)
        val backAskButton = findViewById<Button>(R.id.backAskButton)

        backAskButton.setOnClickListener {
            finish()
        }
        answerText = findViewById(R.id.askAnswerText)
        saathiView = findViewById(R.id.saathiViewAsk)

        queryProcessor = QueryProcessor(this)
        textToSpeech = TextToSpeech(this, this)

        askButton.setOnClickListener {
            processQuestion()
        }
    }

    private fun processQuestion() {

        val question = questionInput.text.toString().trim()

        if (question.isEmpty()) {
            answerText.text = "Please type a question first."
            return
        }

        val result = queryProcessor.processQuery(question)

        if (result != null) {

            answerText.text = result.answer
            speakAnswer(result.answer)

        } else {

            val message =
                "I'm sorry, I don't have information about that yet."

            answerText.text = message
            speakAnswer(message)
        }
    }

    private fun speakAnswer(answer: String) {

        saathiView.setSpeaking(true)

        textToSpeech.speak(
            answer,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SemmozhiAnswer"
        )

        val duration = (answer.length * 55L)
            .coerceIn(2000L, 8000L)

        saathiView.postDelayed({
            saathiView.setSpeaking(false)
        }, duration)
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            textToSpeech.language = Locale.ENGLISH
            textToSpeech.setSpeechRate(0.95f)
        }
    }

    override fun onDestroy() {

        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        super.onDestroy()
    }
}