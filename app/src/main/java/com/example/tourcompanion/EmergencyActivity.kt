package com.example.tourcompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class EmergencyActivity : AppCompatActivity() {

    private lateinit var call112Button: Button
    private lateinit var backEmergencyButton: Button
    private lateinit var saathiView: SaathiView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        setContentView(R.layout.activity_emergency)

        call112Button = findViewById(R.id.call112Button)
        backEmergencyButton = findViewById(R.id.backEmergencyButton)
        saathiView = findViewById(R.id.saathiViewEmergency)

        call112Button.setOnClickListener {

            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:112")

            startActivity(intent)
        }

        backEmergencyButton.setOnClickListener {

            val intent = Intent(
                this,
                MainActivity::class.java
            )

            startActivity(intent)

            finish()
        }
    }
}