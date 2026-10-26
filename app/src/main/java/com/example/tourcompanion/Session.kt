package com.example.tourcompanion

import android.content.Context

/** Who is using the app right now: a logged-in user, a guest, or nobody yet. */
object Session {

    private const val FILE = "tourcompanion_session"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun userId(context: Context): Long = sp(context).getLong("user_id", -1L)
    fun userName(context: Context): String = sp(context).getString("user_name", "") ?: ""
    fun userEmail(context: Context): String = sp(context).getString("user_email", "") ?: ""

    fun isLoggedIn(context: Context): Boolean = userId(context) > 0

    fun isGuest(context: Context): Boolean = sp(context).getBoolean("guest", false)

    /** True when the welcome / login screen has nothing left to ask. */
    fun hasChosen(context: Context): Boolean = isLoggedIn(context) || isGuest(context)

    fun logIn(context: Context, user: User) {
        sp(context).edit()
            .putLong("user_id", user.id)
            .putString("user_name", user.name)
            .putString("user_email", user.email)
            .putBoolean("guest", false)
            .apply()
    }

    fun continueAsGuest(context: Context) {
        sp(context).edit().clear().putBoolean("guest", true).apply()
    }

    fun logOut(context: Context) {
        sp(context).edit().clear().apply()
    }
}
