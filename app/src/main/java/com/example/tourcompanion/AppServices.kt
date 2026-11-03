package com.example.tourcompanion

import android.content.Context

/** Holds objects that should exist only once for the whole app. */
object AppServices {

    @Volatile
    private var processor: QueryProcessor? = null

    @Volatile
    private var users: UserRepository? = null

    @Volatile
    private var travelTips: TravelTipsRepository? = null

    @Volatile
    private var usefulPhrases: UsefulPhrasesRepository? = null

    @Volatile
    private var safety: SafetyRepository? = null

    fun userRepository(context: Context): UserRepository =
        users ?: synchronized(this) {
            users ?: UserRepository(context.applicationContext).also { users = it }
        }

    fun queryProcessor(context: Context): QueryProcessor =
        processor ?: synchronized(this) {
            processor ?: QueryProcessor(context.applicationContext).also { processor = it }
        }

    fun travelTipsRepository(context: Context): TravelTipsRepository =
        travelTips ?: synchronized(this) {
            travelTips ?: TravelTipsRepository(context.applicationContext).also { travelTips = it }
        }

    fun usefulPhrasesRepository(context: Context): UsefulPhrasesRepository =
        usefulPhrases ?: synchronized(this) {
            usefulPhrases ?: UsefulPhrasesRepository(context.applicationContext).also { usefulPhrases = it }
        }

    fun safetyRepository(context: Context): SafetyRepository =
        safety ?: synchronized(this) {
            safety ?: SafetyRepository(context.applicationContext).also { safety = it }
        }
}
