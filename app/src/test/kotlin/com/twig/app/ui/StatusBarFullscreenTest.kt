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
        assertEquals(1, root.paddingLeft)
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

    @Test
    fun `music insets omit the top gap but keep navigation padding and normal status space`() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 80, 0, 0))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(10, 30, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 20))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200)).build()
        assertEquals(Insets.of(0, 0, 0, 20), StatusBarFullscreen.contentInsets(insets, fullscreen = true))
        assertEquals(Insets.of(10, 80, 0, 20), StatusBarFullscreen.contentInsets(insets, fullscreen = false))
    }

    @Test
    fun `landscape fills either side cutout while preserving navigation and keyboard space`() {
        for (cutout in listOf(Insets.of(80, 0, 0, 0), Insets.of(0, 0, 80, 0))) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val root = FrameLayout(activity)
            val content = android.view.View(activity)
            root.addView(content, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            StatusBarFullscreen(activity, root).apply(true)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 24, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200)).build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 800, 400)
            assertEquals(0, content.left)
            assertEquals(776, content.right)
            assertEquals(200, content.bottom)
        }
    }

    @Test
    @Config(sdk = [24, 28])
    fun `music keeps its edge to edge background when leaving fullscreen`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        StatusBarFullscreen.configureWindow(activity, fullscreen = true, fittedWhenNormal = false)
        StatusBarFullscreen.configureWindow(activity, fullscreen = false, fittedWhenNormal = false)
        assertTrue(activity.window.decorView.systemUiVisibility and
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
    }

}
