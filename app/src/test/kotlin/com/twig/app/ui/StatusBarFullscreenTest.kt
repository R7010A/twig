package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusBarFullscreenTest {
    @Test
    @Config(sdk = [24, 28])
    fun `legacy Android can enter and exit fullscreen safely`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val mode = StatusBarFullscreen(activity, root)
        mode.apply(true)
        if (Build.VERSION.SDK_INT >= 28) assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            activity.window.attributes.layoutInDisplayCutoutMode)
        mode.apply(false)
        if (Build.VERSION.SDK_INT >= 28) assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            activity.window.attributes.layoutInDisplayCutoutMode)
        assertEquals(0, root.paddingTop)
    }

    @Test
    fun `fullscreen removes the top cutout band while retaining side navigation and keyboard insets`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.setPadding(1, 2, 3, 4)
        val toolbar = android.view.View(activity)
        root.addView(toolbar, FrameLayout.LayoutParams(300, 56))
        activity.setContentView(root)
        val mode = StatusBarFullscreen(activity, root)
        mode.apply(true)
        if (Build.VERSION.SDK_INT >= 28) assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            activity.window.attributes.layoutInDisplayCutoutMode)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 80, 0, 0))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(10, 30, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 20))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200)).build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertEquals(11, root.paddingLeft)
        assertEquals(2, root.paddingTop)
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 600)
        assertEquals("toolbar must move into the former status-bar/cutout area", 2, toolbar.top)
        assertEquals(204, root.paddingBottom)
        mode.apply(false)
        assertEquals(1, root.paddingLeft)
        assertEquals(2, root.paddingTop)
        assertEquals(4, root.paddingBottom)
    }
}
