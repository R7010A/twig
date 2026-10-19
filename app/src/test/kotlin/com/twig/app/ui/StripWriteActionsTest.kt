package com.twig.app.ui

import android.Manifest
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.core.XFile
import com.twig.core.isMutable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * ★ Checking a file has to re-evaluate the action strip's write entries.
 *
 * The strip's Move / Rename / Delete follow **what the selection points at**
 * (`PaneFragment.stripActions` → `selectionOrCurrent`), but checking a row changes no tree
 * state at all — no `render`, so no `onClipTargetChanged` either. The callback the adapter
 * fires for exactly this event was an empty placeholder, so the strip kept the answer it
 * had computed at the last navigation and nothing recomputed it:
 *
 * 1. left pane on a local directory, switch to the right pane (empty → greyed out),
 * 2. right pane: open an SFTP favorite, check `dosbox-0.74-3.conf`,
 * 3. Move / Rename / Delete **still greyed out** on a perfectly writable SFTP directory,
 * 4. switch to the left pane and back → `setActiveIndex` recomputes as a side effect and
 *    suddenly they work.
 *
 * The strip was showing the state of the *previous* directory, not of the selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StripWriteActionsTest {

    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        // Without storage permission MainActivity only puts up the permission dialog and never
        // builds the panes -- sdk 28 is what keeps that on the grantable legacy check.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        activity = Robolectric.buildActivity(MainActivity::class.java).create().start().get()
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Every strip instance (landscape middle + portrait left/right) has to agree — they all show the same state. */
    private fun writeActions(): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
            val root = activity.findViewById<View>(strip) ?: continue
            for ((name, id) in listOf("move" to R.id.as_move, "rename" to R.id.as_rename, "delete" to R.id.as_delete)) {
                out += "$name@$strip" to root.findViewById<View>(id).isEnabled
            }
        }
        return out
    }

    private fun assertAllWriteActions(enabled: Boolean, why: String) {
        val states = writeActions()
        assertEquals(9, states.size)
        val wrong = states.filter { it.second != enabled }
        assertTrue(why, wrong.isEmpty())
    }

    private fun pane0(): PaneFragment =
        activity.supportFragmentManager.findFragmentByTag("pane0") as PaneFragment

    private fun pane1(): PaneFragment =
        activity.supportFragmentManager.findFragmentByTag("pane1") as PaneFragment

    @Test
    fun `holding any Up icon scrolls to the top while preserving the open list and selection`() {
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val navigation = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .first { it is android.widget.ImageButton && it.drawable === toolbar.navigationIcon }
        val controls = listOf(navigation) + listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right).map {
            activity.findViewById<View>(it).findViewById<View>(R.id.as_up)
        }
        val pane = pane0()
        val vm = pane.viewModel
        vm.expandGroup("fav")
        idle()
        val state = vm.state.value
        val adapter = adapterOf(pane)
        val nodes = (0 until 80).map {
            PaneViewModel.FileNode(XFile("file", "/scroll/row$it.txt", false), 1, false, false)
        }
        adapter.submitList(nodes)
        awaitList(adapter, nodes.size)
        adapter.setSelection(listOf(nodes[50].file))
        val list = pane.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        fun layout() {
            list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 400, 400)
        }
        for (control in controls) {
            manager.scrollToPositionWithOffset(50, 0)
            layout()
            assertEquals(50, manager.findFirstVisibleItemPosition())
            assertTrue(control.performLongClick())
            layout()
            assertEquals(0, manager.findFirstVisibleItemPosition())
            assertEquals(state, vm.state.value)
            assertEquals(nodes, adapter.currentList)
            assertEquals(listOf(nodes[50].file), adapter.selectedItems())
        }
    }

    private fun assertStrip(id: Int, enabled: Boolean) {
        for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
            val button = activity.findViewById<View>(strip).findViewById<View>(id)
            assertEquals("enabled state for $id in $strip", enabled, button.isEnabled)
            assertEquals(if (enabled) 1f else 0.35f, button.alpha, 0.01f)
        }
    }

    private fun adapterOf(pane: PaneFragment): FileAdapter =
        ReflectionHelpers.getField<FileAdapter>(pane, "adapter")

    /** A checked row has to be in the adapter's current list — that is what `selectedItems()` filters. */
    private fun awaitList(adapter: FileAdapter, size: Int) {
        repeat(200) {
            idle()
            if (adapter.currentList.size == size) return
            Thread.sleep(10)
        }
        assertEquals("the rows never landed, so a selection over them could not be asserted", size, adapter.currentList.size)
    }

    @Test
    fun `checking a writable file enables the write actions without switching panes`() {
        val pane = pane0()
        assertTrue("the pane's view is not built, nothing to assert", pane.isReady())
        val adapter = adapterOf(pane)

        // Nothing checked, and the freshly bootstrapped tree has no green-highlighted file to
        // fall back on: this is the state the user was looking at when the buttons were dead.
        adapter.submitList(emptyList())
        idle()
        assertAllWriteActions(false, "no selection at all → nothing to modify")

        val writable = XFile("file", "/storage/emulated/0/Download/dosbox-0.74-3.conf", isDir = false)
        val readOnly = XFile("zip", "/a.zip/b.conf", isDir = false, canWrite = false)
        assertTrue("sanity: the local source must count as mutable", writable.isMutable())
        assertFalse("sanity: an archive entry must not", readOnly.isMutable())

        val nodes = listOf(
            PaneViewModel.FileNode(writable, depth = 1, expandable = false, expanded = false),
            PaneViewModel.FileNode(readOnly, depth = 1, expandable = false, expanded = false),
        )
        adapter.submitList(nodes)
        awaitList(adapter, nodes.size)

        // What a long-press "Select" does.
        adapter.setSelection(listOf(writable))
        assertAllWriteActions(true, "a checked writable file makes Move / Rename / Delete usable, no pane switch needed")

        adapter.setSelection(listOf(readOnly))
        assertAllWriteActions(false, "checking a read-only entry greys them out again")

        adapter.clearSelection()
        assertAllWriteActions(false, "and unchecking greys them out again")
    }
    @Test
    fun `virtual groups disable directory actions while transfers depend only on sources`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        val dir = XFile("file", "/destination", isDir = true)
        pane1().viewModel.currentDir = dir
        pane.viewModel.toggle(PaneViewModel.GroupNode("fav", "Favorites", expanded = false))
        idle()
        activity.onClipTargetChanged()
        for (id in listOf(R.id.as_copy, R.id.as_compress, R.id.as_compare, R.id.as_map, R.id.as_search)) {
            assertStrip(id, false)
        }

        ReflectionHelpers.setField(pane, "mapMode", true)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_map, true) // The same button exits the map, even without a directory target.
        ReflectionHelpers.setField(pane, "mapMode", false)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_map, false)

        val files = listOf("a.txt", "b.txt").map { XFile("file", "/source/$it", isDir = false) }
        adapter.submitList(files.map { PaneViewModel.FileNode(it, 1, false, false) })
        awaitList(adapter, 2)
        adapter.setSelection(files)
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
        assertStrip(R.id.as_delete, true)
        assertStrip(R.id.as_rename, false)
        assertStrip(R.id.as_search, false)

        pane1().viewModel.currentDir = dir.copy(canWrite = false)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
        assertStrip(R.id.as_delete, true)

        pane.viewModel.currentDir = XFile("file", "/source", isDir = true)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_compare, true)
        assertStrip(R.id.as_map, true)
        assertStrip(R.id.as_search, true)
        pane1().viewModel.currentDir = null
        activity.onClipTargetChanged()
        assertStrip(R.id.as_compare, true)
        activity.findViewById<View>(R.id.strip_left).findViewById<View>(R.id.as_compare).performClick()
        assertEquals(activity.getString(R.string.compare_need_two),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
    }

    @Test
    fun `favorite and storage roots can be read without enabling mutation`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        adapter.submitList(emptyList())
        awaitList(adapter, 0)
        pane1().viewModel.currentDir = null
        val targets = listOf(
            "fav:test" to XFile("file", "/favorite", isDir = true),
            "file:/" to XFile("file", "/", isDir = true),
            "file:internal" to XFile("file", android.os.Environment.getExternalStorageDirectory().absolutePath, isDir = true),
        )
        for ((key, dir) in targets) {
            ReflectionHelpers.setField(pane.viewModel, "currentKey", key)
            pane.viewModel.currentDir = dir
            activity.onClipTargetChanged()
            assertStrip(R.id.as_copy, true)
            assertStrip(R.id.as_compress, true)
            assertAllWriteActions(false, "a read source must not enable mutation of $key")
            // No target: both handlers must reach destination validation rather than reject the source.
            pane.actionCopy(move = false)
            assertEquals(activity.getString(R.string.msg_pick_other_dir_first),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            pane.actionCompress()
            assertEquals(activity.getString(R.string.msg_pick_other_dir_first),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
        pane.viewModel.toggle(PaneViewModel.GroupNode("fav", "Favorites", expanded = false))
        activity.onClipTargetChanged()
        assertStrip(R.id.as_copy, false)
        assertStrip(R.id.as_compress, false)
    }

}
