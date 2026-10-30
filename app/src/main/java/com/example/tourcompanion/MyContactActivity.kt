package com.example.tourcompanion

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Saves one trusted contact on this phone only and helps you reach them quickly. */
class MyContactActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_my_contact)
        EdgeToEdge.apply(this)

        nameInput = findViewById(R.id.contactName)
        phoneInput = findViewById(R.id.contactPhone)

        nameInput.setText(Prefs.contactName(this))
        phoneInput.setText(Prefs.contactPhone(this))

        findViewById<Button>(R.id.saveContactButton).setOnClickListener {
            val phone = ContactActions.cleanNumber(phoneInput.text.toString())
            if (phone.length < 5) {
                Toast.makeText(this, R.string.contact_invalid, Toast.LENGTH_SHORT).show()
            } else {
                Prefs.saveContact(this, nameInput.text.toString().trim(), phone)
                phoneInput.setText(phone)
                Toast.makeText(this, R.string.contact_saved, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.callContactButton).setOnClickListener {
            withSavedPhone { ContactActions.dial(this, it) }
        }

        findViewById<Button>(R.id.smsContactButton).setOnClickListener {
            withSavedPhone { ContactActions.sms(this, it, getString(R.string.safe_share_message)) }
        }

        findViewById<Button>(R.id.backContactButton).setOnClickListener { finish() }
    }

    private fun withSavedPhone(action: (String) -> Unit) {
        val phone = Prefs.contactPhone(this)
        if (phone.isBlank()) {
            Toast.makeText(this, R.string.contact_none_saved, Toast.LENGTH_SHORT).show()
        } else {
            action(phone)
        }
    }
}
