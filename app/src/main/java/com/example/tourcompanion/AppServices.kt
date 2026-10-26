package com.example.tourcompanion

import android.content.Context

/** Holds objects that should exist only once for the whole app. */
object AppServices {

    @Volatile
    private var processor: QueryProcessor? = null

    @Volatile
    private var users: UserRepository? = null

    fun userRepository(context: Context): UserRepository =
        users ?: synchronized(this) {
            users ?: UserRepository(context.applicationContext).also { users = it }
        }

    fun queryProcessor(context: Context): QueryProcessor =
        processor ?: synchronized(this) {
            processor ?: QueryProcessor(context.applicationContext).also { processor = it }
        }
}
