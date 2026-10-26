package com.example.tourcompanion

import android.content.Context

/**
 * Settings and saved data, stored privately on the phone.
 *  - Voice settings are shared by everyone using the phone.
 *  - The emergency contact and packing ticks belong to ONE person: they are saved under the
 *    logged-in user's id (or "guest"), so a second account never sees someone else's data.
 */
object Prefs {

    private const val FILE = "tourcompanion_prefs"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun scope(context: Context): String {
        val id = Session.userId(context)
        return if (id > 0) "u${id}_" else "guest_"
    }

    // ---- shared settings
    fun voiceEnabled(context: Context): Boolean = sp(context).getBoolean("voice_enabled", true)
    fun setVoiceEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("voice_enabled", value).apply()

    fun speechRate(context: Context): Float = sp(context).getFloat("speech_rate", 0.95f)
    fun setSpeechRate(context: Context, value: Float) =
        sp(context).edit().putFloat("speech_rate", value).apply()

    // ---- per-person data
    fun contactName(context: Context): String =
        sp(context).getString(scope(context) + "contact_name", "") ?: ""

    fun contactPhone(context: Context): String =
        sp(context).getString(scope(context) + "contact_phone", "") ?: ""

    fun saveContact(context: Context, name: String, phone: String) =
        sp(context).edit()
            .putString(scope(context) + "contact_name", name)
            .putString(scope(context) + "contact_phone", phone)
            .apply()

    fun packed(context: Context): Set<String> =
        HashSet(sp(context).getStringSet(scope(context) + "packed", emptySet()) ?: emptySet())

    fun setPacked(context: Context, items: Set<String>) =
        sp(context).edit().putStringSet(scope(context) + "packed", HashSet(items)).apply()

    /** Settings screen: clears the current person's saved data and resets voice settings. */
    fun clearAll(context: Context) {
        deleteWithPrefix(context, scope(context))
        sp(context).edit().remove("voice_enabled").remove("speech_rate").apply()
    }

    /** Account deletion: removes everything saved for that user. */
    fun deleteUserData(context: Context, userId: Long) {
        deleteWithPrefix(context, "u${userId}_")
    }

    private fun deleteWithPrefix(context: Context, prefix: String) {
        val preferences = sp(context)
        val editor = preferences.edit()
        for (key in preferences.all.keys) {
            if (key.startsWith(prefix)) editor.remove(key)
        }
        editor.apply()
    }
}
