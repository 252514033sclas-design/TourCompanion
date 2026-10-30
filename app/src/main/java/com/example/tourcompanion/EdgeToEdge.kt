package com.example.tourcompanion

import android.app.Activity
import android.graphics.Color
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

    private fun collect(view: View, out: MutableList<View>, tag: String) {
        if (view.tag == tag) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out, tag)
        }
    }
}
