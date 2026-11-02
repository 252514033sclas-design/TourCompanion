package com.example.tourcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Semmozhi — the friendly virtual travel companion.
 *
 * The class name remains SaathiView so existing layouts
 * and activities do not need to be changed.
 *
 * The character uses five images with different mouth states:
 *
 * semmozhi_rest
 * semmozhi_a
 * semmozhi_e
 * semmozhi_o
 * semmozhi_m
 *
 * Put these images inside:
 *
 * app/src/main/res/drawable-nodpi/
 *
 * Public API:
 * setSpeaking(true/false)
 * isSpeaking
 *
 * While speaking, the mouth cycles through the different
 * mouth images and the character makes subtle movements.
 */
class SaathiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------
    // Speaking state
    // ---------------------------------------------------------

    @Volatile
    private var talking = false

    val isSpeaking: Boolean
        get() = talking

    fun setSpeaking(speaking: Boolean) {
        if (talking == speaking) {
            return
        }

        talking = speaking
        postInvalidateOnAnimation()
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

    private val bitmaps =
        HashMap<Mouth, Bitmap?>()

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
    // Small character movement
    // ---------------------------------------------------------

    private var bob = 0f
    private var tilt = 0f

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
    // Load character images
    // ---------------------------------------------------------

    init {

        val options =
            BitmapFactory.Options().apply {
                inScaled = false
            }

        // Direct R.drawable references (not getIdentifier) so release builds
        // never strip these images.
        for (mouth in Mouth.entries) {
            bitmaps[mouth] =
                BitmapFactory.decodeResource(
                    resources,
                    mouth.resId,
                    options
                )
        }
    }

    // ---------------------------------------------------------
    // View size
    // ---------------------------------------------------------

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {

        val density =
            resources.displayMetrics.density

        val defaultSize =
            (DEFAULT_DP * density).toInt()

        val width =
            resolveSize(
                defaultSize,
                widthMeasureSpec
            )

        val height =
            resolveSize(
                defaultSize,
                heightMeasureSpec
            )

        setMeasuredDimension(
            width,
            height
        )
    }

    // ---------------------------------------------------------
    // Draw character
    // ---------------------------------------------------------

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val now =
            SystemClock.uptimeMillis()

        update(now)

        val currentBitmap =
            bitmaps[currentMouth]
                ?: bitmaps[Mouth.REST]

        if (
            currentBitmap != null &&
            width > 0 &&
            height > 0
        ) {

            // Fit character inside the available view.
            val size =
                min(width, height) * 0.96f

            val left =
                (width - size) / 2f

            val top =
                (height - size) / 2f +
                        bob * size

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

                paint.alpha =
                    (fade * 255f).toInt()

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

        // Continue animation only when required.
        if (
            talking ||
            fade < 1f ||
            abs(bob) > 0.0004f ||
            abs(tilt) > 0.02f
        ) {

            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------
    // Update animation
    // ---------------------------------------------------------

    private fun update(now: Long) {

        var target =
            currentMouth

        // -----------------------------------------------------
        // Mouth animation while speaking
        // -----------------------------------------------------

        if (talking) {

            if (now >= nextSwitch) {

                target =
                    talkPattern[
                        patternIndex % talkPattern.size
                    ]

                patternIndex++

                nextSwitch =
                    now +
                            95L +
                            ((patternIndex * 37) % 70)
            }

        } else {

            // Return to resting mouth.
            target =
                Mouth.REST

            patternIndex = 0
            nextSwitch = 0L
        }

        // -----------------------------------------------------
        // Start smooth transition when mouth changes
        // -----------------------------------------------------

        if (target != currentMouth) {

            previousMouth =
                currentMouth

            currentMouth =
                target

            fadeStart =
                now

            fade = 0f
        }

        if (fade < 1f) {

            fade =
                min(
                    1f,
                    (now - fadeStart) /
                            FADE_MS
                )
        }

        // -----------------------------------------------------
        // Small head movement while speaking
        // -----------------------------------------------------

        val time =
            now.toDouble()

        val targetBob =
            if (talking) {
                (
                        sin(time / 170.0) *
                                0.004
                        ).toFloat()
            } else {
                0f
            }

        val targetTilt =
            if (talking) {
                (
                        sin(time / 520.0) *
                                0.7
                        ).toFloat()
            } else {
                0f
            }

        bob +=
            (targetBob - bob) * 0.25f

        tilt +=
            (targetTilt - tilt) * 0.15f
    }

    // ---------------------------------------------------------
    // Constants
    // ---------------------------------------------------------

    private companion object {

        const val DEFAULT_DP = 240f

        const val FADE_MS = 70f
    }
}