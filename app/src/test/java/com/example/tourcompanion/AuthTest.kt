package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {

    @Test
    fun correctPasswordMatchesAndWrongOneDoesNot() {
        val salt = PasswordHasher.newSalt()
        val stored = PasswordHasher.hash("Tour2026pass", salt)
        assertTrue(PasswordHasher.matches("Tour2026pass", salt, stored))
        assertTrue(!PasswordHasher.matches("tour2026pass", salt, stored))
        assertTrue(!PasswordHasher.matches("", salt, stored))
    }

    @Test
    fun sameSaltAndPasswordGiveSameHashButNewSaltChangesIt() {
        val salt = PasswordHasher.newSalt()
        val a = PasswordHasher.encode(PasswordHasher.hash("abc12345", salt))
        val b = PasswordHasher.encode(PasswordHasher.hash("abc12345", salt))
        val c = PasswordHasher.encode(PasswordHasher.hash("abc12345", PasswordHasher.newSalt()))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun hashIsNotThePlainPassword() {
        val salt = PasswordHasher.newSalt()
        val encoded = PasswordHasher.encode(PasswordHasher.hash("MySecret99", salt))
        assertTrue(!encoded.contains("MySecret99"))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val salt = PasswordHasher.newSalt()
        assertEquals(PasswordHasher.encode(salt), PasswordHasher.encode(PasswordHasher.decode(PasswordHasher.encode(salt))))
    }

    @Test
    fun emailValidation() {
        assertTrue(AuthValidator.isValidEmail("asha@example.com"))
        assertTrue(AuthValidator.isValidEmail(AuthValidator.normalizeEmail("  Asha@Example.COM ")))
        assertEquals("asha@example.com", AuthValidator.normalizeEmail("  Asha@Example.COM "))
        assertTrue(!AuthValidator.isValidEmail("asha@"))
        assertTrue(!AuthValidator.isValidEmail("asha example.com"))
        assertTrue(!AuthValidator.isValidEmail("@example.com"))
        assertTrue(!AuthValidator.isValidEmail(""))
    }

    @Test
    fun passwordRules() {
        assertTrue(AuthValidator.isValidPassword("abcdefg1"))
        assertTrue(!AuthValidator.isValidPassword("short1"))
        assertTrue(!AuthValidator.isValidPassword("onlyletters"))
        assertTrue(!AuthValidator.isValidPassword("12345678"))
    }

    @Test
    fun nameRules() {
        assertTrue(AuthValidator.isValidName("Asha"))
        assertTrue(!AuthValidator.isValidName("A"))
        assertTrue(!AuthValidator.isValidName("   "))
    }
}
