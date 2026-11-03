package com.example.tourcompanion

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * - state
 * - setSpeaking(true/false) (preserved for backward compatibility)
 * - isSpeaking
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
    // Mouth states
    // ---------------------------------------------------------

    private enum class Mouth(
        @DrawableRes val resId: Int
    ) {
        REST(R.drawable.semmozhi_rest),
        A(R.drawable.semmozhi_a),
        E(R.drawable.semmozhi_e),
        O(R.drawable.semmozhi_o),
        M(R.drawable.semmozhi_m)
    }

    private val bitmaps: Map<Mouth, Bitmap?> = getOrDecodeBitmaps(resources)

    // Natural-looking mouth animation pattern.
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
    // Small character movement & scale
    // ---------------------------------------------------------

    private var stateEnteredAt = SystemClock.uptimeMillis()
    private var bob = 0f
    private var tilt = 0f
    private var poseScale = 1f

    // ---------------------------------------------------------
    // Drawing objects
    // ---------------------------------------------------------

    private val paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG or
                    Paint.DITHER_FLAG
        )

    private val destination =
        RectF()

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
        patternIndex = 0
        nextSwitch = 0L
        previousMouth = Mouth.REST
        currentMouth = Mouth.REST
        fade = 1f
        bob = 0f
        tilt = 0f
        poseScale = 1f
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------
    // Draw character
    // ---------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        update(now)

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

        if (
            currentState != State.IDLE ||
            idleSettling ||
            fade < 1f ||
            abs(bob) > 0.0004f ||
            abs(tilt) > 0.02f ||
            abs(poseScale - 1f) > 0.0008f
        ) {
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------
    // Update animation
    // ---------------------------------------------------------

    private fun update(now: Long) {
        var target = currentMouth

        when (currentState) {
            State.SPEAKING -> {
                if (now >= nextSwitch) {
                    target = talkPattern[patternIndex % talkPattern.size]
                    patternIndex++
                    nextSwitch = now + 95L + ((patternIndex * 37) % 70)
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
    }

    // ---------------------------------------------------------
    // Shared Bitmap Cache & Constants
    // ---------------------------------------------------------

    private companion object {
        const val DEFAULT_DP = 240f
        const val FADE_MS = 70f
        const val IDLE_SETTLE_MS = 2800L

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
