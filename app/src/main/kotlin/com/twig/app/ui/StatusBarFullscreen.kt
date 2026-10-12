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
                val safe = contentInsets(insets, fullscreen = true, includeKeyboard = true)
                root.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
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
        configureWindow(activity, fullscreen)
        if (!fullscreen) root.setPadding(left, top, right, bottom)
        ViewCompat.requestApplyInsets(root)
    }

    companion object {
        /** Music remains edge-to-edge even with the status bar visible; its own views apply insets. */
        fun configureWindow(activity: Activity, fullscreen: Boolean, fittedWhenNormal: Boolean = true) {
            if (Build.VERSION.SDK_INT >= 28) {
                activity.window.attributes = activity.window.attributes.apply {
                    layoutInDisplayCutoutMode = if (fullscreen)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            WindowCompat.setDecorFitsSystemWindows(activity.window, fittedWhenNormal && !fullscreen)
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }

        /** A hidden status bar and top cutout contribute no top gap, including while bars peek in. */
        fun contentInsets(insets: WindowInsetsCompat, fullscreen: Boolean, includeKeyboard: Boolean = false): androidx.core.graphics.Insets {
            val barTypes = WindowInsetsCompat.Type.navigationBars() or
                (if (includeKeyboard) WindowInsetsCompat.Type.ime() else 0) or
                (if (fullscreen) 0 else WindowInsetsCompat.Type.statusBars())
            val bars = insets.getInsets(barTypes)
            val safe = insets.getInsets(barTypes or WindowInsetsCompat.Type.displayCutout())
            return androidx.core.graphics.Insets.of(safe.left, if (fullscreen) bars.top else safe.top, safe.right, safe.bottom)
        }
    }

}
