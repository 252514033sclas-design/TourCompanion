package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** One screen for both "Log in" and "Create account", plus "Continue as guest". */
class AuthActivity : AppCompatActivity() {

    private var registerMode = false

    private lateinit var titleText: TextView
    private lateinit var subtitle: TextView
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var errorText: TextView
    private lateinit var submitButton: Button
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_auth)
        EdgeToEdge.apply(this)

        titleText = findViewById(R.id.authTitle)
        subtitle = findViewById(R.id.authSubtitle)
        nameInput = findViewById(R.id.authName)
        emailInput = findViewById(R.id.authEmail)
        passwordInput = findViewById(R.id.authPassword)
        errorText = findViewById(R.id.authError)
        submitButton = findViewById(R.id.authSubmitButton)
        toggleButton = findViewById(R.id.authToggleButton)

        registerMode = savedInstanceState?.getBoolean(STATE_REGISTER) ?: false
        applyMode()

        findViewById<CheckBox>(R.id.authShowPassword).setOnCheckedChangeListener { _, show ->
            passwordInput.transformationMethod =
                if (show) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            passwordInput.setSelection(passwordInput.text.length)
        }

        toggleButton.setOnClickListener {
            registerMode = !registerMode
            applyMode()
        }

        submitButton.setOnClickListener { submit() }

        findViewById<Button>(R.id.authGuestButton).setOnClickListener {
            Session.continueAsGuest(this)
            goHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REGISTER, registerMode)
    }

    private fun applyMode() {
        errorText.visibility = View.GONE
        nameInput.visibility = if (registerMode) View.VISIBLE else View.GONE
        titleText.setText(if (registerMode) R.string.auth_title_register else R.string.auth_title_login)
        subtitle.setText(if (registerMode) R.string.auth_subtitle_register else R.string.auth_subtitle_login)
        submitButton.setText(if (registerMode) R.string.auth_button_register else R.string.auth_button_login)
        toggleButton.setText(if (registerMode) R.string.auth_switch_to_login else R.string.auth_switch_to_register)
    }

    private fun submit() {
        errorText.visibility = View.GONE
        submitButton.isEnabled = false

        val name = nameInput.text.toString()
        val email = emailInput.text.toString()
        val password = passwordInput.text.toString()
        val repository = AppServices.userRepository(this)

        lifecycleScope.launch {
            val result = if (registerMode) repository.register(name, email, password)
            else repository.login(email, password)

            when (result) {
                is UserRepository.Result.Success -> {
                    Session.logIn(this@AuthActivity, result.user)
                    goHome()
                }
                is UserRepository.Result.Failure -> {
                    errorText.setText(result.message)
                    errorText.visibility = View.VISIBLE
                    submitButton.isEnabled = true
                }
            }
        }
    }

    /** Opens Home as the first screen, so Back cannot return to login after signing in. */
    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private companion object {
        const val STATE_REGISTER = "register_mode"
    }
}
