package com.example.tourcompanion

import android.content.ContentValues
import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Register, log in and delete accounts. All hashing runs off the main thread. */
class UserRepository(context: Context) {

    sealed class Result {
        data class Success(val user: User) : Result()
        data class Failure(@StringRes val message: Int) : Result()
    }

    private val helper = UserDatabaseHelper(context)

    suspend fun register(name: String, email: String, password: String): Result =
        withContext(Dispatchers.Default) {

            val cleanEmail = AuthValidator.normalizeEmail(email)
            val cleanName = name.trim()

            if (!AuthValidator.isValidName(cleanName)) return@withContext Result.Failure(R.string.auth_error_name)
            if (!AuthValidator.isValidEmail(cleanEmail)) return@withContext Result.Failure(R.string.auth_error_email)
            if (!AuthValidator.isValidPassword(password)) return@withContext Result.Failure(R.string.auth_error_password)

            val db = helper.writableDatabase

            db.rawQuery("SELECT 1 FROM users WHERE email = ?", arrayOf(cleanEmail)).use { cursor ->
                if (cursor.moveToFirst()) return@withContext Result.Failure(R.string.auth_error_email_taken)
            }

            val salt = PasswordHasher.newSalt()
            val values = ContentValues().apply {
                put("name", cleanName)
                put("email", cleanEmail)
                put("password_hash", PasswordHasher.encode(PasswordHasher.hash(password, salt)))
                put("salt", PasswordHasher.encode(salt))
                put("created_at", System.currentTimeMillis())
            }

            val id = db.insert("users", null, values)
            if (id == -1L) Result.Failure(R.string.auth_error_generic)
            else Result.Success(User(id, cleanName, cleanEmail))
        }

    suspend fun login(email: String, password: String): Result =
        withContext(Dispatchers.Default) {

            val cleanEmail = AuthValidator.normalizeEmail(email)
            val wrong = Result.Failure(R.string.auth_error_login)

            if (cleanEmail.isEmpty() || password.isEmpty()) return@withContext wrong

            val row = findByEmail(cleanEmail)
            if (row == null) {
                // Do the same amount of work as a real check, so timing does not reveal which e-mails exist.
                PasswordHasher.hash(password, DUMMY_SALT)
                return@withContext wrong
            }

            if (PasswordHasher.matches(password, row.salt, row.hash)) Result.Success(row.user) else wrong
        }

    /** Deletes the account only if [password] is correct. Returns true on success. */
    suspend fun deleteAccount(userId: Long, password: String): Boolean =
        withContext(Dispatchers.Default) {

            val db = helper.writableDatabase
            val row = db.rawQuery(
                "SELECT id, name, email, password_hash, salt FROM users WHERE id = ?",
                arrayOf(userId.toString())
            ).use { if (it.moveToFirst()) readRow(it) else null } ?: return@withContext false

            if (!PasswordHasher.matches(password, row.salt, row.hash)) return@withContext false

            db.delete("users", "id = ?", arrayOf(userId.toString())) > 0
        }

    private class Row(val user: User, val hash: ByteArray, val salt: ByteArray)

    private fun findByEmail(email: String): Row? =
        helper.readableDatabase.rawQuery(
            "SELECT id, name, email, password_hash, salt FROM users WHERE email = ?",
            arrayOf(email)
        ).use { if (it.moveToFirst()) readRow(it) else null }

    private fun readRow(cursor: android.database.Cursor): Row = Row(
        user = User(cursor.getLong(0), cursor.getString(1), cursor.getString(2)),
        hash = PasswordHasher.decode(cursor.getString(3)),
        salt = PasswordHasher.decode(cursor.getString(4))
    )

    private companion object {
        val DUMMY_SALT = ByteArray(16)
    }
}
