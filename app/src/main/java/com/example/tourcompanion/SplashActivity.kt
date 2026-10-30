package com.example.tourcompanion

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val goNext = Runnable {
        // First time (or after logging out): welcome / login screen. Otherwise straight to Home.
        val next = if (Session.hasChosen(this)) MainActivity::class.java else AuthActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        EdgeToEdge.apply(this, lightNavigationBar = false)

        // Load the travel tips in the background while the splash is showing.
        val appContext = applicationContext
        Thread {
            runCatching { AppServices.queryProcessor(appContext).warmUp() }
        }.start()

        handler.postDelayed(goNext, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goNext)
        super.onDestroy()
    }
}
