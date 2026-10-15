package com.twig.app.ui

import android.app.Application
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.FileInfo
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CurrentDirFrameTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun node(path: String, depth: Int, dir: Boolean = false) =
        PaneViewModel.FileNode(XFile("file", path, dir), depth, expandable = dir, expanded = dir)
    private fun info(owner: PaneViewModel.FileNode) = PaneViewModel.InfoNode(owner.file, owner.depth + 1,
        FileInfo.Details(listOf(FileInfo.Section("General", listOf("Name" to owner.file.name)))))

    private fun assertFrameEndsAt(rows: List<PaneViewModel.Node>, selected: PaneViewModel.Node, end: PaneViewModel.Node) {
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}).apply { currentKey = selected.key }
        adapter.submitList(rows)
        val list = RecyclerView(ContextThemeWrapper(app, R.style.Theme_Twig)).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            itemAnimator = null
        }
        list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 600, 1200)
        assertSame(adapter, list.adapter)
        assertTrue("laid out rows: ${list.childCount}", list.childCount > 0)
        assertEquals(0, list.getChildAdapterPosition(list.getChildAt(0)))
        assertEquals(rows.lastIndex, list.getChildAdapterPosition(list.getChildAt(list.childCount - 1)))
        var drawn: RectF? = null
        var stroke = 0f
        val canvas = object : Canvas() {
            override fun drawRect(rect: RectF, paint: Paint) {
                drawn = RectF(rect)
                stroke = paint.strokeWidth
            }
        }
        CurrentDirFrame(app).onDrawOver(canvas, list, RecyclerView.State())
        val frame = requireNotNull(drawn)
        val firstView = list.findViewHolderForAdapterPosition(rows.indexOf(selected))!!.itemView
        val lastView = list.findViewHolderForAdapterPosition(rows.indexOf(end))!!.itemView
        assertTrue(lastView.height > 0)
        assertEquals(firstView.top + stroke / 2, frame.top, 0.01f)
        assertEquals("frame includes the last child's attached Properties card",
            lastView.bottom - stroke / 2, frame.bottom, 0.01f)
    }

    @Test fun `last direct child properties are inside the parent frame for files and directories`() {
        for (dir in listOf(false, true)) {
            val parent = node("/parent", 0, true)
            val first = node("/parent/first.txt", 1)
            val last = node(if (dir) "/parent/last" else "/parent/last.txt", 1, dir)
            val card = info(last)
            assertFrameEndsAt(listOf(parent, first, last, card, node("/next", 0, true)), parent, card)
        }
    }

    @Test fun `properties do not make the parent frame extend into grandchildren`() {
        val parent = node("/parent", 0, true)
        val child = node("/parent/child", 1, true)
        val card = info(child)
        val grandchild = node("/parent/child/file.txt", 2)
        assertFrameEndsAt(listOf(parent, child, card, grandchild, info(grandchild)), parent, card)
    }

    @Test fun `selected nodes keep their own properties inside the frame`() {
        val selected = node("/parent/file.txt", 1)
        val card = info(selected)
        assertFrameEndsAt(listOf(selected, card, node("/parent/next.txt", 1)), selected, card)
    }
}
