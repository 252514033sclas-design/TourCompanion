package com.example.tourcompanion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ContactActions {

    /** Keeps only digits and a leading +, so it is safe to put in a tel: or smsto: address. */
    fun cleanNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (raw.trim().startsWith("+")) "+$digits" else digits
    }

    fun dial(context: Context, number: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${cleanNumber(number)}")))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.dialer_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the SMS app with the message ready. The user presses send. */
    fun sms(context: Context, number: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${cleanNumber(number)}"))
            intent.putExtra("sms_body", message)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.sms_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
