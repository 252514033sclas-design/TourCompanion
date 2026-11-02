package com.example.tourcompanion

import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class UserRepository(context: android.content.Context) {

    sealed class Result {
        data class Success(val user: User) : Result()
        data class Failure(@StringRes val message: Int) : Result()
    }

    private val api = AuthApiClient()

    suspend fun register(
        name: String,
        email: String,
        password: String
    ): Result = withContext(Dispatchers.IO) {

        val cleanName = name.trim()
        val cleanEmail = AuthValidator.normalizeEmail(email)

        if (!AuthValidator.isValidName(cleanName)) {
            return@withContext Result.Failure(R.string.auth_error_name)
        }

        if (!AuthValidator.isValidEmail(cleanEmail)) {
            return@withContext Result.Failure(R.string.auth_error_email)
        }

        if (!AuthValidator.isValidPassword(password)) {
            return@withContext Result.Failure(R.string.auth_error_password)
        }

        val response = api.register(
            cleanName,
            cleanEmail,
            password
        )

        if (response == null) {
            return@withContext Result.Failure(
                R.string.auth_error_generic
            )
        }

        if (response.optString("status") != "ok") {

            val message = response.optString(
                "message",
                "Unable to create account"
            )

            return@withContext Result.Failure(
                getErrorResource(message)
            )
        }

        val userId = response.optLong("user_id", 0L)

        if (userId <= 0L) {
            return@withContext Result.Failure(
                R.string.auth_error_generic
            )
        }

        Result.Success(
            User(
                userId,
                cleanName,
                cleanEmail
            )
        )
    }

    suspend fun login(
        email: String,
        password: String
    ): Result = withContext(Dispatchers.IO) {

        val cleanEmail = AuthValidator.normalizeEmail(email)

        if (cleanEmail.isEmpty() || password.isEmpty()) {
            return@withContext Result.Failure(
                R.string.auth_error_login
            )
        }

        val response = api.login(
            cleanEmail,
            password
        )

        if (response == null) {
            return@withContext Result.Failure(
                R.string.auth_error_login
            )
        }

        if (response.optString("status") != "ok") {
            return@withContext Result.Failure(
                R.string.auth_error_login
            )
        }

        val userObject = response.optJSONObject("user")

        if (userObject == null) {
            return@withContext Result.Failure(
                R.string.auth_error_login
            )
        }

        val userId = userObject.optLong("id", 0L)
        val name = userObject.optString("name")
        val userEmail = userObject.optString("email")
        val language = userObject.optString("language", "English")
        val createdAt = userObject.optString("created_at", "")

        if (userId <= 0L || name.isEmpty() || userEmail.isEmpty()) {
            return@withContext Result.Failure(
                R.string.auth_error_login
            )
        }

        Result.Success(
            User(
                userId,
                name,
                userEmail,
                language,
                createdAt
            )
        )
    }

    suspend fun getProfile(userId: Long): User? = withContext(Dispatchers.IO) {
        if (userId <= 0L) return@withContext null
        val response = api.getProfile(userId) ?: return@withContext null
        if (response.optString("status") != "ok") return@withContext null
        val userObject = response.optJSONObject("user") ?: return@withContext null

        val id = userObject.optLong("id", 0L)
        val name = userObject.optString("name")
        val email = userObject.optString("email")
        val language = userObject.optString("language", "English")
        val createdAt = userObject.optString("created_at", "")

        if (id <= 0L || name.isEmpty()) return@withContext null
        User(id, name, email, language, createdAt)
    }

    suspend fun deleteAccount(
        userId: Long,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        false
    }

    private fun getErrorResource(message: String): Int {
        return when {
            message.contains(
                "already registered",
                ignoreCase = true
            ) -> R.string.auth_error_email_taken

            message.contains(
                "Invalid email",
                ignoreCase = true
            ) -> R.string.auth_error_email

            message.contains(
                "password",
                ignoreCase = true
            ) -> R.string.auth_error_password

            else -> R.string.auth_error_generic
        }
    }
}
