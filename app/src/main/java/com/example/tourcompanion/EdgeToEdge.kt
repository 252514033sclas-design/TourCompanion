package com.example.tourcompanion

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import kotlin.math.max

/**
 * Draws the screen behind the status bar and navigation bar (required on Android 15+) and then
 * pushes content back out of the way:
 *   - views tagged "header" get extra top padding, so their gradient flows behind the status bar
 *   - views tagged "bottom" (or the root, if there are none) get extra bottom padding for the gesture bar
 *   - the on-screen keyboard also lifts the bottom of the screen
 */
object EdgeToEdge {

    fun apply(activity: Activity, lightNavigationBar: Boolean = true) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return

        val controller = WindowInsetsControllerCompat(window, root)
        controller.isAppearanceLightStatusBars = false          // white clock/icons on the teal header
        controller.isAppearanceLightNavigationBars = lightNavigationBar

        val headers = ArrayList<View>()
        val bottoms = ArrayList<View>()
        collect(root, headers, "header")
        collect(root, bottoms, "bottom")

        val headerBase = headers.map { it.paddingTop }
        val bottomBase = bottoms.map { it.paddingBottom }
        val rootBase = root.paddingBottom

        // Pre-apply system insets synchronously before first frame draw to eliminate vertical jump
        val initialBarsTop = computeInitialTopInset(activity, root)
        val initialBarsBottom = computeInitialBottomInset(activity, root)

        if (initialBarsTop > 0) {
            headers.forEachIndexed { i, view -> view.updatePadding(top = headerBase[i] + initialBarsTop) }
        }
        if (initialBarsBottom > 0) {
            if (bottoms.isEmpty()) {
                root.updatePadding(bottom = rootBase + initialBarsBottom)
            } else {
                bottoms.forEachIndexed { i, view -> view.updatePadding(bottom = bottomBase[i] + initialBarsBottom) }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, keyboard.bottom)

            headers.forEachIndexed { i, view -> view.updatePadding(top = headerBase[i] + bars.top) }

            if (bottoms.isEmpty()) {
                root.updatePadding(bottom = rootBase + bottomInset)
            } else {
                bottoms.forEachIndexed { i, view -> view.updatePadding(bottom = bottomBase[i] + bottomInset) }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun computeInitialTopInset(activity: Activity, root: View): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val windowMetrics = activity.windowManager.currentWindowMetrics
                val insets = WindowInsetsCompat.toWindowInsetsCompat(windowMetrics.windowInsets, root)
                val top = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                ).top
                if (top > 0) return top
            }
        }
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }

    private fun computeInitialBottomInset(activity: Activity, root: View): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val windowMetrics = activity.windowManager.currentWindowMetrics
                val insets = WindowInsetsCompat.toWindowInsetsCompat(windowMetrics.windowInsets, root)
                val bottom = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                ).bottom
                if (bottom > 0) return bottom
            }
        }
        val resId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }

    private fun collect(view: View, out: MutableList<View>, tag: String) {
        if (view.tag == tag) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out, tag)
        }
    }
}
