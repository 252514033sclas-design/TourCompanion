package com.example.tourcompanion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EmergencyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_emergency)
        EdgeToEdge.apply(this)

        // ACTION_DIAL only opens the dialer with 112 filled in - no permission needed,
        // and the user presses call themselves.
        findViewById<Button>(R.id.call112Button).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.dialer_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.backEmergencyButton).setOnClickListener { finish() }
    }
}
