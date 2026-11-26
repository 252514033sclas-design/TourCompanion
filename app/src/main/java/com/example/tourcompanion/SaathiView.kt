package com.example.tourcompanion

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Semmozhi — the friendly virtual travel companion.
 *
 * The class name remains SaathiView so existing layouts
 * and activities do not need to be changed.
 *
 * Public API:
 * - setState(State.IDLE / LISTENING / THINKING / SPEAKING)
 * - setEmergencyMood(active: Boolean)   ← Phase 3A
 * - state
 * - setSpeaking(true/false) (preserved for backward compatibility)
 * - isSpeaking
 * - onSpeechRange(text, start, end) (Phase 2: text-driven lip-sync)
 */
class SaathiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------
    // Character states
    // ---------------------------------------------------------

    enum class State {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    @Volatile
    private var currentState: State = State.IDLE

    val state: State
        get() = currentState

    val isSpeaking: Boolean
        get() = currentState == State.SPEAKING

    fun setState(newState: State) {
        if (currentState == newState) {
            return
        }

        currentState = newState
        stateEnteredAt = SystemClock.uptimeMillis()

        if (newState != State.SPEAKING) {
            patternIndex = 0
            nextSwitch = 0L
            rangeCallbacksReceived = 0
            lastRangeCallbackMs = 0L
            textDrivenMouth = Mouth.REST
            textDrivenMouthUntil = 0L
        }

        postInvalidateOnAnimation()
    }

    fun setSpeaking(speaking: Boolean) {
        if (speaking) {
            setState(State.SPEAKING)
        } else if (currentState == State.SPEAKING) {
            setState(State.IDLE)
        } else {
            // Ensure THINKING or LISTENING returns to IDLE when Speaker.stop() is called
            setState(State.IDLE)
        }
    }

    // ---------------------------------------------------------
    // Phase 3A: Emergency Mood
    // ---------------------------------------------------------

    @Volatile
    private var emergencyMood = false

    /**
     * Activates or deactivates the emergency aura (pulsing red).
     * Call setEmergencyMood(true) from EmergencyActivity or SafetyCheckInActivity
     * when the user is in distress. Call setEmergencyMood(false) when returning to normal.
     */
    fun setEmergencyMood(active: Boolean) {
        if (emergencyMood == active) return
        emergencyMood = active
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------
    // Mouth states & Visemes
    // ---------------------------------------------------------

    private enum class Mouth(
        @param:DrawableRes val resId: Int
    ) {
        REST(R.drawable.semmozhi_rest),
        A(R.drawable.semmozhi_a),
        E(R.drawable.semmozhi_e),
        O(R.drawable.semmozhi_o),
        M(R.drawable.semmozhi_m)
    }

    private val bitmaps: Map<Mouth, Bitmap?> = getOrDecodeBitmaps(resources)

    // Fallback mouth animation pattern (used when onRangeStart is unavailable).
    private val talkPattern = arrayOf(
        Mouth.A,
        Mouth.E,
        Mouth.M,
        Mouth.O,
        Mouth.A,
        Mouth.E,
        Mouth.O,
        Mouth.M,
        Mouth.A,
        Mouth.REST,
        Mouth.E,
        Mouth.A,
        Mouth.O,
        Mouth.E,
        Mouth.M,
        Mouth.A,
        Mouth.O,
        Mouth.REST
    )

    private var patternIndex = 0
    private var nextSwitch = 0L

    private var previousMouth = Mouth.REST
    private var currentMouth = Mouth.REST

    private var fadeStart = 0L
    private var fade = 1f

    // ---------------------------------------------------------
    // Text-driven Viseme Lip-Sync (Phase 2)
    // ---------------------------------------------------------

    @Volatile
    private var rangeCallbacksReceived = 0

    @Volatile
    private var lastRangeCallbackMs = 0L

    private var textDrivenMouth = Mouth.REST
    private var textDrivenMouthUntil = 0L

    /**
     * Receives word-range timing from Android TTS onRangeStart().
     * Maps the spoken substring to an approximate mouth viseme.
     */
    fun onSpeechRange(text: String, start: Int, end: Int) {
        if (currentState != State.SPEAKING) return
        if (start < 0 || end > text.length || start >= end) return

        val now = SystemClock.uptimeMillis()
        rangeCallbacksReceived++
        lastRangeCallbackMs = now

        val word = text.substring(start, end)
        val viseme = VisemeMapper.map(word, text, start, end)

        textDrivenMouth = when (viseme) {
            VisemeMapper.Viseme.REST -> Mouth.REST
            VisemeMapper.Viseme.A -> Mouth.A
            VisemeMapper.Viseme.E -> Mouth.E
            VisemeMapper.Viseme.O -> Mouth.O
            VisemeMapper.Viseme.M -> Mouth.M
        }

        // Hold the viseme for natural word articulation; brief hold for punctuation
        val holdDuration = if (textDrivenMouth == Mouth.REST) 140L else 190L
        textDrivenMouthUntil = now + holdDuration

        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------
    // Small character movement & scale
    // ---------------------------------------------------------

    private var stateEnteredAt = SystemClock.uptimeMillis()
    private var bob = 0f
    private var tilt = 0f
    private var poseScale = 1f

    // ---------------------------------------------------------
    // Phase 3A: Aura animation state
    // ---------------------------------------------------------

    // Smoothed aura alpha and radius fraction (interpolated each frame)
    private var auraAlpha = 0f
    private var auraRadius = 0.5f

    // Tracks the character's actual drawn center Y so the aura follows bob/tilt
    private var charCenterY = 0f

    // ---------------------------------------------------------
    // Drawing objects
    // ---------------------------------------------------------

    private val paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG or
                    Paint.DITHER_FLAG
        )

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val destination = RectF()

    // ---------------------------------------------------------
    // View size
    // ---------------------------------------------------------

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val density = resources.displayMetrics.density
        val defaultSize = (DEFAULT_DP * density).toInt()

        val width = resolveSize(defaultSize, widthMeasureSpec)
        val height = resolveSize(defaultSize, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    // ---------------------------------------------------------
    // Lifecycle safety
    // ---------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stateEnteredAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        currentState = State.IDLE
        emergencyMood = false
        patternIndex = 0
        nextSwitch = 0L
        rangeCallbacksReceived = 0
        lastRangeCallbackMs = 0L
        textDrivenMouth = Mouth.REST
        textDrivenMouthUntil = 0L
        previousMouth = Mouth.REST
        currentMouth = Mouth.REST
        fade = 1f
        bob = 0f
        tilt = 0f
        poseScale = 1f
        auraAlpha = 0f
        auraRadius = 0.5f
        charCenterY = 0f
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------
    // Draw character
    // ---------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        update(now)

        // -------------------------------------------------
        // Phase 3A: Draw aura behind the character
        // Compute character center (follows bob) so the aura is always aligned.
        // -------------------------------------------------
        if (width > 0 && height > 0) {
            val size = min(width, height) * 0.96f * poseScale
            charCenterY = height / 2f + bob * size
        }
        if (width > 0 && height > 0 && auraAlpha > 0.005f) {
            drawAura(canvas, now)
        }

        val currentBitmap =
            bitmaps[currentMouth]
                ?: bitmaps[Mouth.REST]

        if (
            currentBitmap != null &&
            width > 0 &&
            height > 0
        ) {
            // Fit character inside the available view with subtle pose scale.
            val size = min(width, height) * 0.96f * poseScale
            val left = (width - size) / 2f
            val top = (height - size) / 2f + bob * size

            destination.set(
                left,
                top,
                left + size,
                top + size
            )

            canvas.save()

            canvas.rotate(
                tilt,
                width / 2f,
                top + size
            )

            // -------------------------------------------------
            // Smooth mouth transition
            // -------------------------------------------------

            if (
                fade < 1f &&
                previousMouth != currentMouth
            ) {
                val previousBitmap =
                    bitmaps[previousMouth]
                        ?: bitmaps[Mouth.REST]

                if (previousBitmap != null) {
                    paint.alpha = 255
                    canvas.drawBitmap(
                        previousBitmap,
                        null,
                        destination,
                        paint
                    )
                }

                paint.alpha = (fade * 255f).toInt()
            } else {
                paint.alpha = 255
            }

            canvas.drawBitmap(
                currentBitmap,
                null,
                destination,
                paint
            )

            paint.alpha = 255
            canvas.restore()
        }

        // Continue animation only when required (battery-efficient).
        val idleSettling =
            currentState == State.IDLE && (now - stateEnteredAt) < IDLE_SETTLE_MS

        // Keep animating if the aura still has visible alpha or if in an active state
        val auraActive = currentState != State.IDLE || emergencyMood || auraAlpha > 0.005f

        if (
            currentState != State.IDLE ||
            idleSettling ||
            auraActive ||
            fade < 1f ||
            abs(bob) > 0.0004f ||
            abs(tilt) > 0.02f ||
            abs(poseScale - 1f) > 0.0008f
        ) {
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------
    // Phase 3A: Aura drawing
    // ---------------------------------------------------------

    private fun drawAura(canvas: Canvas, now: Long) {
        val cx = width / 2f
        // cy follows the character's actual drawn center (accounts for bob drift)
        val cy = if (charCenterY > 0f) charCenterY else height / 2f
        val maxR = min(width, height) * auraRadius

        // Build radial gradient: color center → transparent edge
        val gradient = RadialGradient(
            cx, cy, maxR,
            intArrayOf(
                Color.argb((auraAlpha * 255f).toInt(), auraCenterR, auraCenterG, auraCenterB),
                Color.argb(0, auraCenterR, auraCenterG, auraCenterB)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        auraPaint.shader = gradient
        canvas.drawCircle(cx, cy, maxR, auraPaint)
    }

    // ---------------------------------------------------------
    // Update animation
    // ---------------------------------------------------------

    private var auraCenterR = 27
    private var auraCenterG = 184
    private var auraCenterB = 161  // teal by default

    private fun update(now: Long) {
        var target = currentMouth

        when (currentState) {
            State.SPEAKING -> {
                // If onRangeStart callbacks are actively arriving, use text-driven visemes.
                // Otherwise seamlessly fall back to talkPattern.
                val hasRecentTextRange = rangeCallbacksReceived > 0 && (now - lastRangeCallbackMs) < 650L

                if (hasRecentTextRange) {
                    target = if (now <= textDrivenMouthUntil) {
                        textDrivenMouth
                    } else {
                        // Natural relaxation to REST between words or after punctuation
                        Mouth.REST
                    }
                } else {
                    // Fallback to existing talkPattern
                    if (now >= nextSwitch) {
                        target = talkPattern[patternIndex % talkPattern.size]
                        patternIndex++
                        nextSwitch = now + 95L + ((patternIndex * 37) % 70)
                    }
                }
            }

            State.THINKING -> {
                // Pursed thinking expression
                target = Mouth.M
                patternIndex = 0
                nextSwitch = 0L
            }

            State.LISTENING, State.IDLE -> {
                target = Mouth.REST
                patternIndex = 0
                nextSwitch = 0L
            }
        }

        // -----------------------------------------------------
        // Start smooth transition when mouth changes
        // -----------------------------------------------------

        if (target != currentMouth) {
            previousMouth = currentMouth
            currentMouth = target
            fadeStart = now
            fade = 0f
        }

        if (fade < 1f) {
            fade = min(1f, (now - fadeStart) / FADE_MS)
        }

        // -----------------------------------------------------
        // State-specific head movement & pose
        // -----------------------------------------------------

        val time = now.toDouble()
        val elapsedInState = now - stateEnteredAt

        val targetBob: Float
        val targetTilt: Float
        val targetScale: Float

        when (currentState) {
            State.SPEAKING -> {
                targetBob = (sin(time / 170.0) * 0.004).toFloat()
                targetTilt = (sin(time / 520.0) * 0.7).toFloat()
                targetScale = 1f
            }

            State.THINKING -> {
                // Subtle curious head tilt, gentle vertical sway, and light thinking pulse
                targetBob = (sin(time / 320.0) * 0.003).toFloat()
                targetTilt = 1.8f + (sin(time / 450.0) * 0.6).toFloat()
                targetScale = 1f + (sin(time / 360.0) * 0.006).toFloat()
            }

            State.LISTENING -> {
                // Attentive slight lean-in and subtle lively breath while mic is open
                targetBob = -0.006f + (sin(time / 400.0) * 0.002).toFloat()
                targetTilt = -0.6f + (sin(time / 650.0) * 0.3).toFloat()
                targetScale = 1.025f
            }

            State.IDLE -> {
                // Bounded gentle breathing and head drift that smoothly settles to rest (no infinite loop)
                if (elapsedInState < IDLE_SETTLE_MS) {
                    val envelope = 1f - (elapsedInState.toFloat() / IDLE_SETTLE_MS)
                    targetBob = (sin(time / 550.0) * 0.0025 * envelope).toFloat()
                    targetTilt = (sin(time / 900.0) * 0.35 * envelope).toFloat()
                    targetScale = 1f + (sin(time / 650.0) * 0.0035 * envelope).toFloat()
                } else {
                    targetBob = 0f
                    targetTilt = 0f
                    targetScale = 1f
                }
            }
        }

        bob += (targetBob - bob) * 0.20f
        tilt += (targetTilt - tilt) * 0.15f
        poseScale += (targetScale - poseScale) * 0.18f

        // -----------------------------------------------------
        // Phase 3A: Update aura alpha and radius
        // Emergency mood overrides all state colors with pulsing red.
        // -----------------------------------------------------
        updateAura(now)
    }

    private fun updateAura(now: Long) {
        val time = now.toDouble()

        // Target aura parameters vary by state and emergency mood
        val targetAlpha: Float
        val targetRadius: Float

        if (emergencyMood) {
            // Emergency: pulsing red aura, moderate alpha, faster pulse
            val pulse = ((sin(time / 320.0) + 1.0) / 2.0).toFloat()  // 0..1
            targetAlpha = 0.15f + pulse * 0.10f   // 0.15..0.25
            targetRadius = 0.60f + pulse * 0.05f  // 0.60..0.65
            auraCenterR = 220
            auraCenterG = 38
            auraCenterB = 38   // danger red #DC2626
        } else {
            // Normal states: calm teal palette
            auraCenterR = 27
            auraCenterG = 184
            auraCenterB = 161   // primary_light teal #1BB8A1

            when (currentState) {
                State.IDLE -> {
                    val elapsedInState = now - stateEnteredAt
                    if (elapsedInState < IDLE_SETTLE_MS) {
                        // Brief settling glow fades out gracefully
                        val envelope = 1f - (elapsedInState.toFloat() / IDLE_SETTLE_MS)
                        val pulse = ((sin(time / 1800.0) + 1.0) / 2.0).toFloat()
                        targetAlpha = (0.05f + pulse * 0.02f) * envelope
                        targetRadius = 0.56f + pulse * 0.02f * envelope
                    } else {
                        // Fully settled: aura OFF (0% CPU)
                        targetAlpha = 0f
                        targetRadius = 0.56f
                    }
                }

                State.LISTENING -> {
                    // Slightly brighter, steady glow — Semmozhi is alert
                    val pulse = ((sin(time / 900.0) + 1.0) / 2.0).toFloat()
                    targetAlpha = 0.12f + pulse * 0.05f   // 0.12..0.17
                    targetRadius = 0.58f + pulse * 0.03f  // 0.58..0.61
                }

                State.THINKING -> {
                    // Gentle animated pulse — processing
                    val pulse = ((sin(time / 600.0) + 1.0) / 2.0).toFloat()
                    targetAlpha = 0.10f + pulse * 0.07f   // 0.10..0.17
                    targetRadius = 0.57f + pulse * 0.04f  // 0.57..0.61
                }

                State.SPEAKING -> {
                    // Slightly stronger, loose speech pulse — not phoneme-exact
                    val pulse = ((sin(time / 380.0) + 1.0) / 2.0).toFloat()
                    targetAlpha = 0.13f + pulse * 0.08f   // 0.13..0.21
                    targetRadius = 0.58f + pulse * 0.05f  // 0.58..0.63
                }
            }
        }

        // Smooth interpolation so transitions are soft, never jerky
        auraAlpha += (targetAlpha - auraAlpha) * AURA_LERP
        auraRadius += (targetRadius - auraRadius) * AURA_LERP
    }

    // ---------------------------------------------------------
    // Shared Bitmap Cache & Constants
    // ---------------------------------------------------------

    private companion object {
        const val DEFAULT_DP = 240f
        const val FADE_MS = 70f
        const val IDLE_SETTLE_MS = 2800L
        const val AURA_LERP = 0.06f   // smooth cross-fade speed for aura transitions

        @Volatile
        private var sharedBitmaps: Map<Mouth, Bitmap?>? = null

        fun getOrDecodeBitmaps(resources: Resources): Map<Mouth, Bitmap?> {
            sharedBitmaps?.let { cached ->
                if (cached.values.all { it != null && !it.isRecycled }) {
                    return cached
                }
            }
            return synchronized(this) {
                sharedBitmaps?.let { cached ->
                    if (cached.values.all { it != null && !it.isRecycled }) {
                        return@synchronized cached
                    }
                }
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                }
                val decoded = EnumMap<Mouth, Bitmap?>(Mouth::class.java)
                for (mouth in Mouth.entries) {
                    decoded[mouth] = BitmapFactory.decodeResource(
                        resources,
                        mouth.resId,
                        options
                    )
                }
                sharedBitmaps = decoded
                decoded
            }
        }
    }
}
