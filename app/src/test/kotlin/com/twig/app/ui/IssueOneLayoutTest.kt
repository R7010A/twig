package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.app.databinding.ActionStripBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IssueOneLayoutTest {
    @Test
    fun `landscape offers Terminal without sessions and places buttons in the requested rows`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val ctx = ContextThemeWrapper(activity, R.style.Theme_Twig)
        val strip = ActionStripBinding.inflate(LayoutInflater.from(ctx))
        ReflectionHelpers.callInstanceMethod<Void>(activity, "applyStripState",
            ReflectionHelpers.ClassParameter.from(ActionStripBinding::class.java, strip),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        val top = strip.asTop
        top.measure(View.MeasureSpec.makeMeasureSpec(208, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        top.layout(0, 0, top.measuredWidth, top.measuredHeight)
        assertEquals(4, top.childCount)
        assertEquals(View.VISIBLE, strip.asTerm.visibility)
        assertEquals(strip.asTerm.top, strip.asSwap.top)
        assertEquals(strip.asMusic.top, strip.asMenu.top)
        assertTrue(strip.asMusic.top > strip.asTerm.top)
        assertEquals(strip.asTerm.left, strip.asMusic.left)
        assertEquals(strip.asSwap.left, strip.asMenu.left)
        assertTrue(strip.asSwap.left > strip.asTerm.left)
    }
}
