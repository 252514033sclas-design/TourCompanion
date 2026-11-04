package com.example.tourcompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Wraps TextToSpeech and drives the SaathiView mouth from real speech events.
 * Integrates onRangeStart() to provide text-driven viseme lip-sync while preserving
 * fallback animation when range events are unavailable.
 */
class Speaker(
    context: Context,
    private val avatar: SaathiView
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var ready = false
    private var failed = false
    private var pendingText: String? = null
    private var pendingLocale: Locale = DEFAULT_LOCALE

    @Volatile
    private var currentId = ""

    @Volatile
    private var currentText = ""

    private var counter = 0

    /** Called on the main thread when the speech engine finishes starting (or fails). */
    var onStateChanged: (() -> Unit)? = null

    // Created last, so every field above exists before onInit() can run.
    private val tts = TextToSpeech(appContext, this)

    val isReady: Boolean get() = ready
    val hasFailed: Boolean get() = failed

    val engineName: String
        get() = try {
            tts.defaultEngine ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    fun isLanguageAvailable(locale: Locale): Boolean =
        ready && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {
            markFailed()
            return
        }

        var result = tts.setLanguage(DEFAULT_LOCALE)
        var activeLocale = DEFAULT_LOCALE
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            val fallbackResult = tts.setLanguage(Locale.ENGLISH)
            if (fallbackResult == TextToSpeech.LANG_MISSING_DATA ||
                fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                markFailed()
                return
            }
            activeLocale = Locale.ENGLISH
        }

        applyBestVoice(activeLocale)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                if (utteranceId == currentId) {
                    main.post {
                        if (utteranceId == currentId) {
                            avatar.setSpeaking(true)
                        }
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // Ignore callbacks from previous or cancelled utterances
                if (utteranceId == currentId && !utteranceId.isNullOrEmpty()) {
                    val spokenText = currentText
                    main.post {
                        if (utteranceId == currentId) {
                            avatar.onSpeechRange(spokenText, start, end)
                        }
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == currentId) {
                    main.post {
                        if (utteranceId == currentId) {
                            avatar.setSpeaking(false)
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == currentId) {
                    main.post {
                        if (utteranceId == currentId) {
                            avatar.setSpeaking(false)
                        }
                    }
                }
            }
        })

        ready = true
        pendingText?.let { speak(it, pendingLocale) }
        pendingText = null
        onStateChanged?.invoke()
    }

    private fun markFailed() {
        failed = true
        pendingText?.let { animateOnly(it) }
        pendingText = null
        onStateChanged?.invoke()
    }

    /**
     * Speaks [text]. Returns false if it could not be spoken (engine missing, or no voice
     * installed for [locale]) so the caller can show a hint.
     */
    fun speak(text: String, locale: Locale = DEFAULT_LOCALE): Boolean {

        if (!Prefs.voiceEnabled(appContext)) {
            avatar.setSpeaking(false)
            return true
        }

        if (failed) {
            animateOnly(text)
            return false
        }
        if (!ready) {
            pendingText = text
            pendingLocale = locale
            return true
        }

        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            avatar.setSpeaking(false)
            return false
        }

        applyBestVoice(locale)

        tts.setPitch(DEFAULT_PITCH)
        tts.setSpeechRate(Prefs.speechRate(appContext))

        counter++
        currentId = "semmozhi_$counter"
        currentText = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentId)
        return true
    }

    /**
     * Inspects available voices in TextToSpeech and selects the best natural/local voice
     * matching [locale]. Prioritizes offline voices and Semmozhi female character persona
     * while avoiding legacy Seanet embedded voices when higher-quality alternatives exist.
     */
    private fun applyBestVoice(locale: Locale) {
        try {
            val voices = tts.voices ?: return
            if (voices.isEmpty()) return

            val matchingVoices = voices.filter { voice ->
                val vl = voice.locale
                vl != null && vl.language.equals(locale.language, ignoreCase = true)
            }
            if (matchingVoices.isEmpty()) return

            val bestVoice = matchingVoices.maxByOrNull { voice ->
                var score = 0

                // 1. Exact country match (e.g. IN for en-IN / ta-IN / hi-IN)
                if (locale.country.isNotEmpty() &&
                    voice.locale.country.equals(locale.country, ignoreCase = true)
                ) {
                    score += 50
                }

                // 2. Offline / local voice preferred
                if (!voice.isNetworkConnectionRequired) {
                    score += 30
                }

                val name = voice.name.lowercase(Locale.ROOT)

                // 3. Avoid legacy Seanet embedded voices if alternatives exist
                if (name.contains("seanet-embedded")) {
                    score -= 40
                }

                // 4. Prefer Google TTS high-quality downloaded local packs
                if (name.contains("-local")) {
                    score += 25
                }

                // 5. Prefer Semmozhi female companion voices
                val isPreferredTarget = when {
                    name.contains("ena-local") || name.contains("end-local") -> true
                    name.contains("tac-local") -> true
                    name.contains("hia-local") || name.contains("hid-local") -> true
                    else -> false
                }
                if (isPreferredTarget) {
                    score += 40
                }

                if (voice.features?.any { it.contains("female", ignoreCase = true) } == true) {
                    score += 20
                }

                score += voice.quality
                score
            }

            bestVoice?.let { voice ->
                if (voice.locale.language.equals(locale.language, ignoreCase = true)) {
                    val status = tts.setVoice(voice)
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "Selected voice: ${voice.name} for locale: ${voice.locale}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyBestVoice error, continuing with default TTS voice", e)
        }
    }

    fun stop() {
        pendingText = null
        currentId = ""
        currentText = ""
        main.removeCallbacksAndMessages(null)
        if (ready) tts.stop()
        avatar.setSpeaking(false)
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        currentId = ""
        currentText = ""
        tts.stop()
        tts.shutdown()
    }

    /** Fallback when no voice is installed: just animate the character for a while. */
    private fun animateOnly(text: String) {
        main.removeCallbacksAndMessages(null)
        avatar.setSpeaking(true)
        main.postDelayed(
            { avatar.setSpeaking(false) },
            (text.length * 55L).coerceIn(1500L, 8000L)
        )
    }

    companion object {
        private const val TAG = "Speaker"
        val DEFAULT_LOCALE: Locale = Locale.forLanguageTag("en-IN")
        const val DEFAULT_PITCH: Float = 1.08f
    }
}
