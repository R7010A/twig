package com.twig.app.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.twig.app.R

/** Status-bar-only fullscreen uses the top cutout area without reserving an empty band.
 * Side cutouts, navigation and keyboard still have insets; normal mode is system-fitted. */
class StatusBarFullscreen(private val activity: Activity, private val root: View) {
    private var enabled = false
    private val background = root.background
    private val left = root.paddingLeft
    private val top = root.paddingTop
    private val right = root.paddingRight
    private val bottom = root.paddingBottom

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (enabled) {
                val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
                val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.ime())
                // A top cutout is inside the toolbar in fullscreen, not a blank row above it.
                root.setPadding(left + safe.left, top + bars.top, right + safe.right, bottom + safe.bottom)
                // Descendants must not apply a second copy of the insets already handled here.
                WindowInsetsCompat.CONSUMED
            } else {
                root.setPadding(left, top, right, bottom)
                insets
            }
        }
    }

    fun apply(fullscreen: Boolean) {
        enabled = fullscreen
        if (fullscreen) root.setBackgroundColor(ContextCompat.getColor(activity, R.color.toolbar))
        else root.background = background
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = if (fullscreen)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowCompat.setDecorFitsSystemWindows(activity.window, !fullscreen)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
            root.setPadding(left, top, right, bottom)
        }
        ViewCompat.requestApplyInsets(root)
    }
}
