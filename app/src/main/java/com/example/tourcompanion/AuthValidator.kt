package com.example.tourcompanion

import java.util.Locale

/** Rules for names, e-mail addresses and passwords. Plain Kotlin (unit-tested). */
object AuthValidator {

    const val MIN_PASSWORD_LENGTH = 8

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    fun normalizeEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun isValidEmail(email: String): Boolean = email.length <= 120 && EMAIL.matches(email)

    /** At least 8 characters, with at least one letter and one number. */
    fun isValidPassword(password: String): Boolean =
        password.length >= MIN_PASSWORD_LENGTH &&
                password.any { it.isLetter() } &&
                password.any { it.isDigit() }

    fun isValidName(name: String): Boolean = name.trim().length in 2..60
}
