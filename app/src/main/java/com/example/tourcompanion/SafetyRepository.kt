package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SafetyStatusResult(
    val status: String,
    val timestamp: String,
    val isSynced: Boolean,
    val isGuest: Boolean = false,
    val isOffline: Boolean = false,
    val message: String = ""
)

data class CheckInResult(
    val success: Boolean,
    val status: String,
    val isSynced: Boolean,
    val isGuest: Boolean,
    val message: String
)

class SafetyRepository(
    context: Context,
    private val apiClient: SafetyApiClient = SafetyApiClient()
) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("tourcompanion_safety", Context.MODE_PRIVATE)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun nowFormatted(): String = dateFormat.format(Date())

    suspend fun recordCheckIn(userId: Long, status: String): CheckInResult = withContext(Dispatchers.IO) {
        val now = nowFormatted()

        // Guest handling: do not make API calls or send invalid user IDs
        if (userId <= 0L) {
            Log.d("SafetyRepository", "Guest check-in recorded locally: $status")
            sp.edit()
                .putString("guest_status", status)
                .putString("guest_time", now)
                .apply()

            return@withContext CheckInResult(
                success = true,
                status = status,
                isSynced = false,
                isGuest = true,
                message = "Check-in saved locally on this device (Guest Mode)."
            )
        }

        // Logged-in user: attempt network call
        val response = apiClient.sendCheckIn(userId, status)

        if (response != null && response.optString("status") == "ok") {
            Log.d("SafetyRepository", "Check-in synced to MySQL successfully for user $userId")

            // Cache as latest synced status
            sp.edit()
                .putString("cached_status_$userId", status)
                .putString("cached_time_$userId", now)
                .putBoolean("cached_synced_$userId", true)
                .apply()

            // Try syncing any pending check-ins from previous offline sessions
            syncPendingCheckIns(userId)

            CheckInResult(
                success = true,
                status = status,
                isSynced = true,
                isGuest = false,
                message = "Safety check-in recorded and synchronized."
            )
        } else {
            // Network failed or offline: queue check-in locally
            Log.w("SafetyRepository", "Check-in network call failed; queuing locally for user $userId")
            queuePendingCheckIn(userId, status, now)

            sp.edit()
                .putString("cached_status_$userId", status)
                .putString("cached_time_$userId", now)
                .putBoolean("cached_synced_$userId", false)
                .apply()

            CheckInResult(
                success = true,
                status = status,
                isSynced = false,
                isGuest = false,
                message = "Saved locally. Will sync when internet is restored."
            )
        }
    }

    suspend fun getLatestStatus(userId: Long): SafetyStatusResult = withContext(Dispatchers.IO) {
        // Guest user
        if (userId <= 0L) {
            val status = sp.getString("guest_status", "") ?: ""
            val time = sp.getString("guest_time", "") ?: ""
            return@withContext SafetyStatusResult(
                status = status,
                timestamp = time,
                isSynced = false,
                isGuest = true,
                isOffline = false,
                message = if (status.isNotEmpty()) "Guest check-in stored locally" else "No guest check-in recorded yet"
            )
        }

        // Before reading status, attempt to sync any pending check-ins
        syncPendingCheckIns(userId)

        // Attempt to fetch from backend
        val response = apiClient.getLatestStatus(userId)

        if (response != null && response.optString("status") == "ok") {
            val checkin = response.optJSONObject("checkin")
            if (checkin != null) {
                val status = checkin.optString("status", "")
                val time = checkin.optString("checked_at", "")

                // Cache latest status
                sp.edit()
                    .putString("cached_status_$userId", status)
                    .putString("cached_time_$userId", time)
                    .putBoolean("cached_synced_$userId", true)
                    .apply()

                SafetyStatusResult(
                    status = status,
                    timestamp = time,
                    isSynced = true,
                    isGuest = false,
                    isOffline = false,
                    message = "Latest status from server"
                )
            } else {
                // No check-in found on backend
                SafetyStatusResult(
                    status = "",
                    timestamp = "",
                    isSynced = true,
                    isGuest = false,
                    isOffline = false,
                    message = "No check-ins found"
                )
            }
        } else {
            // Network failure or offline: read local cache
            val cachedStatus = sp.getString("cached_status_$userId", "") ?: ""
            val cachedTime = sp.getString("cached_time_$userId", "") ?: ""
            val isSynced = sp.getBoolean("cached_synced_$userId", false)

            SafetyStatusResult(
                status = cachedStatus,
                timestamp = cachedTime,
                isSynced = isSynced,
                isGuest = false,
                isOffline = true,
                message = if (cachedStatus.isNotEmpty()) "Offline: showing cached status" else "Offline: no status available"
            )
        }
    }

    suspend fun syncPendingCheckIns(userId: Long): Int = withContext(Dispatchers.IO) {
        if (userId <= 0L) return@withContext 0

        val queue = getPendingQueue()
        if (queue.isEmpty()) return@withContext 0

        Log.d("SafetyRepository", "Attempting to sync ${queue.size} pending check-ins for user $userId")

        val remaining = mutableListOf<PendingCheckIn>()
        var syncedCount = 0

        for (item in queue) {
            if (item.userId == userId) {
                val res = apiClient.sendCheckIn(item.userId, item.status)
                if (res != null && res.optString("status") == "ok") {
                    syncedCount++
                    Log.d("SafetyRepository", "Pending check-in [${item.status}] synced successfully")
                } else {
                    // Still failing: retain this and remaining items
                    remaining.add(item)
                    Log.w("SafetyRepository", "Failed to sync pending check-in [${item.status}], halting queue sync")
                    // Do not attempt remaining if connection is down
                    val index = queue.indexOf(item)
                    for (k in (index + 1) until queue.size) {
                        remaining.add(queue[k])
                    }
                    break
                }
            } else {
                remaining.add(item)
            }
        }

        savePendingQueue(remaining)

        if (syncedCount > 0) {
            // Update cached sync flag to true
            sp.edit().putBoolean("cached_synced_$userId", true).apply()
        }

        syncedCount
    }

    private fun queuePendingCheckIn(userId: Long, status: String, timestamp: String) {
        val queue = getPendingQueue().toMutableList()
        queue.add(PendingCheckIn(userId, status, timestamp))
        savePendingQueue(queue)
        Log.d("SafetyRepository", "Queued pending check-in. Total in queue: ${queue.size}")
    }

    private fun getPendingQueue(): List<PendingCheckIn> {
        val raw = sp.getString("pending_queue", null) ?: return emptyList()
        val list = mutableListOf<PendingCheckIn>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    PendingCheckIn(
                        userId = obj.optLong("user_id"),
                        status = obj.optString("status"),
                        timestamp = obj.optString("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error reading pending queue", e)
        }
        return list
    }

    private fun savePendingQueue(list: List<PendingCheckIn>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("user_id", item.userId)
                put("status", item.status)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        sp.edit().putString("pending_queue", array.toString()).apply()
    }

    private data class PendingCheckIn(
        val userId: Long,
        val status: String,
        val timestamp: String
    )
}

