package com.example.tourcompanion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TravelTipsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = TravelTipsApiClient()
    private val dbHelper = TravelDatabaseHelper(appContext)

    /**
     * Downloads travel tips from MySQL via travel-tips.php if online,
     * and syncs them into SQLite without duplicating records.
     * Returns true if sync succeeded, false if offline/error.
     */
    suspend fun syncTips(): Boolean = withContext(Dispatchers.IO) {
        try {
            val remoteTips = api.fetchTravelTips()
            if (remoteTips.isNullOrEmpty()) {
                Log.d("TravelTipsRepository", "No remote tips downloaded (offline or empty). Using SQLite cache.")
                return@withContext false
            }

            val syncedCount = dbHelper.syncRemoteTips(remoteTips)
            Log.d("TravelTipsRepository", "Synced $syncedCount travel tips into SQLite.")

            // Refresh query processor's in-memory matcher with the updated SQLite tips
            AppServices.queryProcessor(appContext).reload()
            true
        } catch (e: Exception) {
            Log.w("TravelTipsRepository", "Sync failed: ${e.message}. Using existing SQLite tips.")
            false
        }
    }
}

