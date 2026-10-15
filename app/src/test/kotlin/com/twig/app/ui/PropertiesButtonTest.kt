package com.twig.app.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PropertiesButtonTest {
    private fun node(dir: Boolean, depth: Int = 1) = PaneViewModel.FileNode(
        XFile("file", if (dir) "/folder" else "/file.txt", dir), depth, dir, false)

    private fun holder(adapter: FileAdapter, position: Int = 0): RecyclerView.ViewHolder {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(ContextThemeWrapper(activity, R.style.Theme_Twig))
        activity.setContentView(parent)
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(position))
        parent.addView(holder.itemView)
        adapter.bindViewHolder(holder, position)
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        holder.itemView.layout(0, 0, 600, holder.itemView.measuredHeight)
        return holder
    }

    @Test fun `hidden circular button stays at the left edge without changing tree indentation`() {
        for (directory in listOf(false, true)) for (depth in listOf(0, 1, 4, 10)) {
            val node = node(directory, depth)
            var opens = 0
            var propertiesOpen = false
            val adapter = FileAdapter(1, { opens++ }, {}, {}, { _, _ -> }, {}, onInfo = {
                assertEquals(node, it)
                propertiesOpen = !propertiesOpen
            })
            adapter.submitList(listOf(node))
            val row = holder(adapter).itemView
            val button = row.findViewById<ImageButton>(R.id.info_toggle)
            val content = row.findViewById<View>(R.id.row_content)
            val indicator = row.findViewById<View>(R.id.indicator)
            val dpi = row.resources.displayMetrics.density
            assertEquals(View.VISIBLE, button.visibility)
            assertNull(button.drawable)
            assertTrue(button.background is RippleDrawable)
            val mask = (button.background as RippleDrawable).findDrawableByLayerId(android.R.id.mask) as GradientDrawable
            assertEquals(GradientDrawable.OVAL, mask.shape)
            assertEquals(0, button.left)
            assertEquals((32 * dpi).toInt(), button.width)
            assertEquals(button.width, button.height)
            assertEquals((44 * dpi).toInt(), row.height)
            assertEquals(((2 + depth * 14) * dpi).toInt(), content.paddingStart)
            assertEquals(content.paddingStart, indicator.left)
            val icon = row.findViewById<View>(R.id.icon)
            assertEquals(content.paddingStart + (18 * dpi).toInt(), icon.left)
            assertEquals(row.context.getString(R.string.file_info), button.contentDescription)
            val x = button.width / 2f
            val y = button.top + button.height / 2f
            fun touch(action: Int, atX: Float = x, atY: Float = y) {
                MotionEvent.obtain(0, 10, action, atX, atY, 0).also {
                    assertTrue(row.dispatchTouchEvent(it))
                    it.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN)
            assertTrue(button.isPressed)
            touch(MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(propertiesOpen)
            assertEquals(0, opens)
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(propertiesOpen)
            if (directory && indicator.right <= button.right) {
                val arrowX = indicator.left + indicator.width / 2f
                val arrowY = indicator.top + indicator.height / 2f
                touch(MotionEvent.ACTION_DOWN, arrowX, arrowY)
                touch(MotionEvent.ACTION_UP, arrowX, arrowY)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(propertiesOpen)
                assertEquals(0, opens)
                button.performClick()
                assertFalse(propertiesOpen)
            }
            // Deep arrows lie outside the fixed Properties button and retain the row's normal action.
            if (directory && indicator.left > button.right) {
                val arrowX = indicator.left + indicator.width / 2f
                val arrowY = indicator.top + indicator.height / 2f
                touch(MotionEvent.ACTION_DOWN, arrowX, arrowY)
                touch(MotionEvent.ACTION_UP, arrowX, arrowY)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, opens)
                assertFalse(propertiesOpen)
            }
        }
    }

    @Test fun `virtual rows clear the properties button when a holder is reused`() {
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(node(false), PaneViewModel.GroupNode("lan", "LAN", false)))
        val row = holder(adapter)
        assertEquals(View.VISIBLE, row.itemView.findViewById<View>(R.id.info_toggle).visibility)
        adapter.onBindViewHolder(row, 1)
        val button = row.itemView.findViewById<View>(R.id.info_toggle)
        assertEquals(View.INVISIBLE, button.visibility)
        assertFalse(button.hasOnClickListeners())
    }

    @Test fun `grid files have the same blank properties button`() {
        val node = node(false)
        var clicks = 0
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, gridMode = 2, onInfo = {
            assertEquals(node, it); clicks++
        })
        adapter.submitList(listOf(node))
        val row = holder(adapter).itemView
        val button = row.findViewById<ImageButton>(R.id.info_toggle)
        assertNull(button.drawable)
        assertTrue(button.background is RippleDrawable)
        button.performClick()
        assertEquals(1, clicks)
    }
}
