package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class UsefulPhrasesActivity : AppCompatActivity() {

    private lateinit var backPhrasesButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_useful_phrases)

        backPhrasesButton = findViewById(R.id.backPhrasesButton)

        backPhrasesButton.setOnClickListener {
            val intent = Intent(
                this,
                MainActivity::class.java
            )
            startActivity(intent)
            finish()
        }
    }
}