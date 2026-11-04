package com.example.tourcompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var saathiView: SaathiView
    private lateinit var questionInput: EditText
    private lateinit var answerText: TextView

    private lateinit var askButton: Button
    private lateinit var voiceButton: Button
    private lateinit var emergencyButton: View

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var panelHome: View
    private lateinit var panelExplore: View
    private lateinit var panelSafety: View
    private lateinit var panelProfile: View

    private var currentTabId: Int = R.id.nav_home
    private var profileNameView: TextView? = null
    private var profileEmailView: TextView? = null
    private var profileLanguageView: TextView? = null
    private var profileJoinedView: TextView? = null
    private var profileBuiltForLoggedIn: Boolean? = null

    private lateinit var speaker: Speaker
    private lateinit var repository: AnswerRepository

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
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()

                if (!spoken.isNullOrBlank()) {
                    questionInput.setText(spoken)
                    answerQuestion(spoken)
                } else {
                    saathiView.setState(SaathiView.State.IDLE)
                }
            } else {
                saathiView.setState(SaathiView.State.IDLE)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        EdgeToEdge.apply(this)

        saathiView = findViewById(R.id.saathiView)
        questionInput = findViewById(R.id.questionInput)
        answerText = findViewById(R.id.answerText)

        askButton = findViewById(R.id.askButton)
        voiceButton = findViewById(R.id.voiceButton)
        emergencyButton = findViewById(R.id.emergencyButton)

        repository = AnswerRepository(this)
        speaker = Speaker(this, saathiView)

        // Ask: answer here if something is typed, otherwise open the full Ask screen.
        askButton.setOnClickListener {
            val question = questionInput.text.toString().trim()
            if (question.isEmpty()) {
                startActivity(Intent(this, AskSemmozhiActivity::class.java))
            } else {
                answerQuestion(question)
            }
        }

        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                askButton.performClick()
                true
            } else {
                false
            }
        }

        voiceButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startVoiceInput()
            }
        }

        emergencyButton.setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }

        setUpTabs()
        setUpHome()
    }

    // ---------------------------------------------------------
    // Home: greeting, quick tiles, suggestion chips
    // ---------------------------------------------------------

    private fun setUpHome() {
        updateGreeting()

        fun tile(id: Int, target: Class<*>) {
            findViewById<View>(id).setOnClickListener { startActivity(Intent(this, target)) }
        }
        tile(R.id.quickTips, TipsHubActivity::class.java)
        tile(R.id.quickPhrases, UsefulPhrasesActivity::class.java)
        tile(R.id.quickPacking, PackingActivity::class.java)
        tile(R.id.quickHelplines, HelplinesActivity::class.java)

        val chips = findViewById<LinearLayout>(R.id.suggestionContainer)
        for (suggestion in resources.getStringArray(R.array.suggestions)) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, chips, false) as TextView
            chip.text = suggestion
            chip.setOnClickListener {
                questionInput.setText(suggestion)
                answerQuestion(suggestion)
            }
            chips.addView(chip)
        }
    }

    private fun updateGreeting() {
        val greeting = findViewById<TextView>(R.id.greetingText)
        val first = Session.userName(this).trim().substringBefore(' ')
        greeting.text = if (Session.isLoggedIn(this) && first.isNotEmpty()) {
            getString(R.string.home_greeting_named, first)
        } else {
            getString(R.string.home_greeting_guest)
        }
    }

    // ---------------------------------------------------------
    // Tabs: Home / Explore / Safety / Profile
    // ---------------------------------------------------------

    private fun setUpTabs() {
        bottomNav = findViewById(R.id.bottomNav)
        panelHome = findViewById(R.id.panelHome)
        panelExplore = findViewById(R.id.panelExplore)
        panelSafety = findViewById(R.id.panelSafety)
        panelProfile = findViewById(R.id.panelProfile)

        val explore = findViewById<LinearLayout>(R.id.exploreContainer)
        row(explore, "🧭", R.string.menu_tips, R.string.menu_tips_sub, TipsHubActivity::class.java)
        row(explore, "🗣️", R.string.menu_phrases, R.string.menu_phrases_sub, UsefulPhrasesActivity::class.java)
        row(explore, "🎒", R.string.menu_packing, R.string.menu_packing_sub, PackingActivity::class.java)
        row(explore, "₹", R.string.menu_budget, R.string.menu_budget_sub, BudgetActivity::class.java)

        val safety = findViewById<LinearLayout>(R.id.safetyContainer)
        row(safety, "SOS", R.string.menu_emergency, R.string.menu_emergency_sub, EmergencyActivity::class.java, "#E52525")
        row(safety, "🛡️", R.string.menu_checkin, R.string.menu_checkin_sub, SafetyCheckInActivity::class.java)
        row(safety, "☎️", R.string.menu_helplines, R.string.menu_helplines_sub, HelplinesActivity::class.java)
        row(safety, "👤", R.string.menu_contact, R.string.menu_contact_sub, MyContactActivity::class.java)

        updateProfileData(fetchRemote = true)

        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != currentTabId) {
                showPanel(item.itemId)
            }
            true
        }

        // Back from another tab returns to Home first; back on Home leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabId != R.id.nav_home) {
                    bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lifecycleScope.launch(Dispatchers.IO) {
            AppServices.travelTipsRepository(applicationContext).syncTips()
        }
    }

    private fun row(
        container: LinearLayout,
        icon: String,
        title: Int,
        subtitle: Int,
        target: Class<*>,
        accent: String? = null
    ) {
        UiKit.addMenuRow(container, icon, getString(title), getString(subtitle), accent) {
            startActivity(Intent(this, target))
        }
    }

    private fun showPanel(itemId: Int) {
        if (itemId == currentTabId) return
        currentTabId = itemId

        panelHome.visibility = if (itemId == R.id.nav_home) View.VISIBLE else View.GONE
        panelExplore.visibility = if (itemId == R.id.nav_explore) View.VISIBLE else View.GONE
        panelSafety.visibility = if (itemId == R.id.nav_safety) View.VISIBLE else View.GONE
        panelProfile.visibility = if (itemId == R.id.nav_profile) View.VISIBLE else View.GONE

        if (itemId == R.id.nav_profile) {
            updateProfileData(fetchRemote = false)
        }
    }

    // ---------------------------------------------------------
    // Profile tab: who you are, tools, log out, delete account
    // ---------------------------------------------------------

    private fun ensureProfileBuilt(loggedIn: Boolean) {
        if (profileBuiltForLoggedIn == loggedIn) {
            return
        }

        val container = findViewById<LinearLayout>(R.id.profileContainer)
        container.removeAllViews()

        val header = LayoutInflater.from(this).inflate(R.layout.item_profile_header, container, false)
        profileNameView = header.findViewById(R.id.profileName)
        profileEmailView = header.findViewById(R.id.profileEmail)
        profileLanguageView = header.findViewById(R.id.profileLanguage)
        profileJoinedView = header.findViewById(R.id.profileJoined)

        container.addView(header)

        row(container, "🎙️", R.string.menu_voice, R.string.menu_voice_sub, VoiceCheckActivity::class.java)
        row(container, "⚙️", R.string.menu_settings, R.string.menu_settings_sub, SettingsActivity::class.java)
        row(container, "ℹ️", R.string.menu_about, R.string.menu_about_sub, AboutActivity::class.java)

        if (loggedIn) {
            UiKit.addMenuRow(container, "↩", getString(R.string.menu_logout), getString(R.string.menu_logout_sub)) {
                confirmLogout()
            }
            UiKit.addMenuRow(
                container, "🗑️", getString(R.string.menu_delete), getString(R.string.menu_delete_sub), "#E52525"
            ) {
                confirmDelete()
            }
        } else {
            UiKit.addMenuRow(container, "🔐", getString(R.string.menu_login), getString(R.string.menu_login_sub)) {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        }

        profileBuiltForLoggedIn = loggedIn
    }

    private fun updateProfileData(fetchRemote: Boolean = false) {
        val loggedIn = Session.isLoggedIn(this)
        ensureProfileBuilt(loggedIn)

        val nameView = profileNameView ?: return
        val emailView = profileEmailView ?: return
        val languageView = profileLanguageView ?: return
        val joinedView = profileJoinedView ?: return

        nameView.text = if (loggedIn) Session.userName(this) else getString(R.string.profile_guest)
        emailView.text = if (loggedIn) Session.userEmail(this) else getString(R.string.profile_guest_sub)

        if (loggedIn) {
            val cachedLang = Session.userLanguage(this)
            if (cachedLang.isNotBlank()) {
                languageView.visibility = View.VISIBLE
                languageView.text = "Language: $cachedLang"
            } else {
                languageView.visibility = View.GONE
            }

            val cachedJoined = Session.userCreatedAt(this)
            if (cachedJoined.isNotBlank()) {
                joinedView.visibility = View.VISIBLE
                joinedView.text = "Joined: $cachedJoined"
            } else {
                joinedView.visibility = View.GONE
            }

            if (fetchRemote) {
                val userId = Session.userId(this)
                lifecycleScope.launch {
                    val profile = AppServices.userRepository(applicationContext).getProfile(userId)
                    if (profile != null) {
                        Session.updateProfile(this@MainActivity, profile)
                        nameView.text = profile.name
                        emailView.text = profile.email
                        if (profile.language.isNotBlank()) {
                            languageView.visibility = View.VISIBLE
                            languageView.text = "Language: ${profile.language}"
                        }
                        if (profile.createdAt.isNotBlank()) {
                            joinedView.visibility = View.VISIBLE
                            joinedView.text = "Joined: ${profile.createdAt}"
                        }
                    }
                }
            }
        } else {
            languageView.visibility = View.GONE
            joinedView.visibility = View.GONE
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setPositiveButton(R.string.logout_confirm) { _, _ ->
                speaker.stop()
                Session.logOut(this)
                openWelcomeScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val password = EditText(this).apply {
            hint = getString(R.string.delete_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val frame = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(password)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setView(frame)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteAccount(password.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteAccount(password: String) {
        val userId = Session.userId(this)

        lifecycleScope.launch {
            val deleted = AppServices.userRepository(applicationContext).deleteAccount(userId, password)

            if (deleted) {
                speaker.stop()
                Prefs.deleteUserData(this@MainActivity, userId)
                Session.logOut(this@MainActivity)
                Toast.makeText(this@MainActivity, R.string.delete_done, Toast.LENGTH_LONG).show()
                openWelcomeScreen()
            } else {
                Toast.makeText(this@MainActivity, R.string.delete_wrong_password, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Starts the login screen as the only screen, so Back cannot return to a logged-out Home. */
    private fun openWelcomeScreen() {
        startActivity(
            Intent(this, AuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just logged in from the Profile tab.
        if (::panelProfile.isInitialized) {
            updateProfileData(fetchRemote = false)
            updateGreeting()
        }
    }

    private fun answerQuestion(question: String) {

        hideKeyboard()
        answerText.text = getString(R.string.thinking)
        setBusy(true)
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
                saathiView.setState(SaathiView.State.IDLE)
            } finally {
                if (saathiView.state == SaathiView.State.THINKING && !Prefs.voiceEnabled(this@MainActivity)) {
                    saathiView.setState(SaathiView.State.IDLE)
                }
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        askButton.isEnabled = !busy
        voiceButton.isEnabled = !busy
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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(questionInput.windowToken, 0)
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
