package com.example.tourcompanion

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AskSemmozhiActivity : AppCompatActivity() {

    private lateinit var questionInput: EditText
    private lateinit var askButton: Button
    private lateinit var answerText: TextView
    private lateinit var saathiView: SaathiView

    private lateinit var speaker: Speaker
    private lateinit var repository: AnswerRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_ask_semmozhi)
        EdgeToEdge.apply(this)

        questionInput = findViewById(R.id.askQuestionInput)
        askButton = findViewById(R.id.askSemmozhiButton)
        answerText = findViewById(R.id.askAnswerText)
        saathiView = findViewById(R.id.saathiViewAsk)

        findViewById<Button>(R.id.backAskButton).setOnClickListener { finish() }

        repository = AnswerRepository(this)
        speaker = Speaker(this, saathiView)

        askButton.setOnClickListener { processQuestion() }

        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                processQuestion()
                true
            } else {
                false
            }
        }
    }

    private fun processQuestion() {

        val question = questionInput.text.toString().trim()

        if (question.isEmpty()) {
            answerText.text = getString(R.string.type_question_first)
            return
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(questionInput.windowToken, 0)

        answerText.text = getString(R.string.thinking)
        askButton.isEnabled = false
        saathiView.setState(SaathiView.State.THINKING)

        lifecycleScope.launch {
            try {
                val answer = repository.answer(question)
                answerText.text = answer.text
                if (!speaker.speak(answer.text)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
            } catch (e: Exception) {
                val fallback = getString(R.string.no_answer)
                answerText.text = fallback
                if (!speaker.speak(fallback)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
            } finally {
                if (saathiView.state == SaathiView.State.THINKING && !Prefs.voiceEnabled(this@AskSemmozhiActivity)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
                askButton.isEnabled = true
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
