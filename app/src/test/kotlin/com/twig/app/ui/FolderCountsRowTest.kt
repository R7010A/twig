package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SavedConnection
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import com.twig.core.FsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderCountsRowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    @After fun tearDown() { Prefs.setFolderCounts(app, false); DirectoryCounts.clear() }
    private val dir = XFile("file", "/storage/Example", true, lastModified = 1700000000000L)
    private fun adapter() = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
    private fun parent() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
    private fun seed() {
        DirectoryCounts.remember(dir, (1..2).map { XFile("file", dir.path + "/Folder$it", true) } +
            (1..5).map { XFile("file", dir.path + "/File$it", false) })
    }

    @Test fun `two count icons sit before the timestamp and share its color`() {
        Prefs.setFolderCounts(app, true)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false)))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val row = holder.itemView
        val width = (400 * app.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        val counts = row.findViewById<TextView>(R.id.folderCounts)
        val meta = row.findViewById<TextView>(R.id.meta)
        assertEquals(View.VISIBLE, counts.visibility)
        assertEquals("\uFFFC 2  \uFFFC 5", counts.text.toString())
        assertEquals(2, (counts.text as Spanned).getSpans(0, counts.length(), ImageSpan::class.java).size)
        assertEquals(meta.currentTextColor, counts.currentTextColor)
        assertTrue("counts align with the start of the directory name", counts.left == 0)
        assertTrue("timestamp stays to the right of both counts", meta.left >= counts.right)
        assertTrue(meta.text.isNotBlank())
        assertEquals(View.VISIBLE, meta.visibility)
        assertTrue("timestamp has usable bounds: ${meta.width} x ${meta.height}", meta.width > 0 && meta.height > 0)
        assertTrue("right-aligned time must be laid out within its visible width", meta.layout.width <= meta.width)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        row.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), "twig-folder-counts.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun `setting is independent defaults off and disabled rows do not request counts`() {
        assertFalse(Prefs.folderCounts(app))
        val before = Prefs.uiSignature(app)
        Prefs.setFolderCounts(app, true)
        assertNotEquals(before, Prefs.uiSignature(app))
        Prefs.setFolderCounts(app, false)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false,
            directoryCount = DirectoryCounts.Count(2, 5))))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val counts = holder.itemView.findViewById<TextView>(R.id.folderCounts)
        assertEquals(View.GONE, counts.visibility)
        assertNull(counts.getTag(R.id.directory_count))
    }

    @Test fun `reusing a directory holder for a file clears its counters`() {
        Prefs.setFolderCounts(app, true)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false),
            PaneViewModel.FileNode(XFile("file", "/file.txt", false, size = 4), 1, false, false)))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<TextView>(R.id.folderCounts).visibility)
        adapter.onBindViewHolder(holder, 1)
        val counts = holder.itemView.findViewById<TextView>(R.id.folderCounts)
        assertEquals(View.GONE, counts.visibility)
        assertNull(counts.getTag(R.id.directory_count))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `ordinary SMB expansion and resume update counts without a separate probe`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val conn = SavedConnection("smb", "counts.test")
        val scheme = Connections.schemeOf(conn)
        val dirs = mutableMapOf("/" to listOf("dir"), "/dir" to listOf("sub", "first.txt"), "/dir/sub" to emptyList())
        val files = mutableMapOf("/dir/first.txt" to "first")
        val fs = FakeFileSystem(scheme, dirs, files)
        FsRegistry.register(fs)
        ConnectionStore.save(app, conn)
        Prefs.setFolderCounts(app, true)
        try {
            val vm = PaneViewModel(app).apply { io = dispatcher }
            vm.revealPath(XFile(scheme, "/dir", true))
            advanceUntilIdle()
            fun count() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .single { it.file.scheme == scheme && it.file.path == "/dir" }.directoryCount
            assertEquals(DirectoryCounts.Count(1, 1), count())
            dirs["/dir"] = listOf("first.txt", "other.txt")
            files["/dir/other.txt"] = "other"
            fs.listed.clear()
            vm.refreshOnResume()
            advanceUntilIdle()
            assertEquals(DirectoryCounts.Count(0, 2), count())
            assertEquals(listOf("/dir"), fs.listed)
        } finally {
            FsRegistry.unregister(scheme)
            Dispatchers.resetMain()
        }
    }

}
