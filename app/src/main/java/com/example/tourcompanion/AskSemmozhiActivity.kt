package com.example.tourcompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class AskSemmozhiActivity : AppCompatActivity() {

    private lateinit var questionInput: EditText
    private lateinit var askButton: Button
    private lateinit var voiceButton: Button
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var emptyChatHint: TextView
    private lateinit var saathiView: SaathiView

    private lateinit var speaker: Speaker
    private lateinit var repository: AnswerRepository
    private lateinit var locationProvider: AppLocationManager
    private val conversationHistory = ConversationHistory()
    private var pendingQuestion: String? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val question = pendingQuestion
            pendingQuestion = null
            if (question.isNullOrBlank()) return@registerForActivityResult

            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            val coords = if (granted) locationProvider.getLastKnownCoordinates() else null
            executeQuestionAnswer(question, coords?.first, coords?.second)
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_SHORT).show()
            }
        }

    private val voiceResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            saathiView.setState(SaathiView.State.IDLE)
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()

                if (!spoken.isNullOrBlank()) {
                    questionInput.setText(spoken)
                    processQuestion()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Clear any previous session destination memory so each session starts fresh
        QueryProcessor.clearSessionDestination()
        conversationHistory.clear()

        supportActionBar?.hide()
        setContentView(R.layout.activity_ask_semmozhi)
        EdgeToEdge.apply(this)

        questionInput = findViewById(R.id.askQuestionInput)
        askButton = findViewById(R.id.askSemmozhiButton)
        voiceButton = findViewById(R.id.askVoiceButton)
        chatScrollView = findViewById(R.id.chatScrollView)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        emptyChatHint = findViewById(R.id.emptyChatHint)
        saathiView = findViewById(R.id.saathiViewAsk)

        findViewById<Button>(R.id.backAskButton).setOnClickListener { finish() }

        repository = AnswerRepository(this)
        speaker = Speaker(this, saathiView)
        locationProvider = AppServices.locationProvider(this)

        askButton.setOnClickListener { processQuestion() }

        voiceButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startVoiceInput()
            }
        }

        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                if (askButton.isEnabled) {
                    processQuestion()
                }
                true
            } else {
                false
            }
        }

        chatMessagesContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scrollToBottom()
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }

        try {
            saathiView.setState(SaathiView.State.LISTENING)
            voiceResultLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            saathiView.setState(SaathiView.State.IDLE)
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun processQuestion() {
        if (!askButton.isEnabled) {
            return
        }

        val question = questionInput.text.toString().trim()

        if (question.isEmpty()) {
            Toast.makeText(this, R.string.type_question_first, Toast.LENGTH_SHORT).show()
            return
        }

        speaker.stop()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(questionInput.windowToken, 0)

        emptyChatHint.visibility = View.GONE

        // 1. Add user message bubble and record user turn in session history
        addUserMessage(question)
        conversationHistory.addUserMessage(question)
        questionInput.setText("")

        val classification = QueryProcessor.classifyTravelIntent(question)
        val needsLocation = classification.intent == TravelIntent.TRANSPORT_NEARBY ||
            classification.intent == TravelIntent.NEARBY_ATTRACTIONS ||
            classification.intent == TravelIntent.DISTANCE_DIRECTIONS

        val coords = if (needsLocation && locationProvider.hasLocationPermission()) {
            locationProvider.getLastKnownCoordinates()
        } else {
            null
        }

        val historySnapshot = conversationHistory.messages()
        executeQuestionAnswer(question, coords?.first, coords?.second, historySnapshot)
    }

    private fun executeQuestionAnswer(
        question: String,
        userLat: Double?,
        userLon: Double?,
        historySnapshot: List<ConversationMessage> = emptyList()
    ) {
        // 2. Add Semmozhi message bubble with loading/thinking state
        val answerTextView = addSemmozhiMessage(getString(R.string.thinking))

        askButton.isEnabled = false
        voiceButton.isEnabled = false
        saathiView.setState(SaathiView.State.THINKING)
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val answer = repository.answer(question, userLat, userLon, historySnapshot)
                conversationHistory.addAssistantMessage(answer.text)
                answerTextView.text = answer.text
                if (!speaker.speak(answer.text)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
            } catch (e: Exception) {
                val fallback = getString(R.string.no_answer)
                answerTextView.text = fallback
                if (!speaker.speak(fallback)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
            } finally {
                if (saathiView.state == SaathiView.State.THINKING && !Prefs.voiceEnabled(this@AskSemmozhiActivity)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
                askButton.isEnabled = true
                voiceButton.isEnabled = true
                scrollToBottom()
            }
        }
    }

    private fun addUserMessage(text: String) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_chat_user, chatMessagesContainer, false)
        val textView = view.findViewById<TextView>(R.id.userMessageText)
        textView.text = text
        chatMessagesContainer.addView(view)
    }

    private fun addSemmozhiMessage(initialText: String): TextView {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_chat_semmozhi, chatMessagesContainer, false)
        val textView = view.findViewById<TextView>(R.id.askAnswerText)
        textView.text = initialText
        chatMessagesContainer.addView(view)
        return textView
    }

    private fun scrollToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onStop() {
        if (saathiView.state == SaathiView.State.LISTENING) {
            saathiView.setState(SaathiView.State.IDLE)
        }
        speaker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        QueryProcessor.clearSessionDestination()
        conversationHistory.clear()
        speaker.shutdown()
        super.onDestroy()
    }
}
