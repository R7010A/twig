package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchRenameTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun file(name: String, dir: Boolean = false) = XFile("batch", "/$name", dir)
    private fun generate(
        files: List<XFile>, part: BatchRenameNames.Part = BatchRenameNames.Part.NAME,
        numbering: Boolean = false, find: String = "old", replacement: String = "new",
        regex: Boolean = false, matchCase: Boolean = false, width: Int = 1,
        start: String = "1", numberFirst: Boolean = false,
    ) = BatchRenameNames.generate(files, part, numbering, find, replacement, regex, matchCase, width, start, numberFirst)

    @Test fun `preview columns stay aligned on first measurement and after rebinding long names`() {
        val row = BatchRenameDialog.NameColumns(context)
        val before = android.widget.TextView(context)
        val after = android.widget.TextView(context)
        row.addView(before, android.widget.LinearLayout.LayoutParams(0, -2))
        row.addView(after, android.widget.LinearLayout.LayoutParams(0, -2))
        for ((old, new) in listOf("a" to "a very long new filename.txt",
            "a very long original filename that wraps onto several lines.txt" to "new.txt",
            "short.txt" to "another.txt")) {
            before.text = old
            after.text = new
            for (mode in listOf(android.view.View.MeasureSpec.AT_MOST, android.view.View.MeasureSpec.EXACTLY)) {
                row.measure(android.view.View.MeasureSpec.makeMeasureSpec(401, mode),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                assertEquals(401, row.measuredWidth)
                assertEquals(200, after.left)
                assertEquals(before.top, after.top)
                assertEquals(200, before.width)
                assertEquals(201, after.width)
            }
        }
    }

    @Test fun `replacement respects scope and case without changing extension capitalization`() {
        val files = listOf(file("Old.old"), file("old.OLD"))
        assertEquals(listOf("new.old", "new.OLD"), generate(files))
        assertEquals(listOf("Old.old", "new.OLD"), generate(files, matchCase = true))
        assertEquals(listOf("Old.new", "old.new"), generate(files, BatchRenameNames.Part.EXTENSION))
        assertEquals(listOf("new.new", "new.new"), generate(files, BatchRenameNames.Part.FULL))
    }

    @Test fun `regex replacement supports capture groups and literal replacement preserves dollar signs`() {
        assertEquals(listOf("Episode 07.MKV"), generate(listOf(file("ep07.MKV")), find = "ep([0-9]+)",
            replacement = "Episode $1", regex = true))
        assertEquals(listOf("$1.txt"), generate(listOf(file("old.txt")), replacement = "$1"))
        assertTrue(runCatching { generate(listOf(file("old.txt")), find = "[", regex = true) }.isFailure)
    }

    @Test fun `numbering follows input order from padded start and preserves extension`() {
        val files = listOf(file("z.JPG"), file("a.JPG"))
        assertEquals(listOf("z - 0004.JPG", "a - 0005.JPG"), generate(files, numbering = true, width = 4, start = "0004"))
        assertEquals(listOf("04 - z.JPG", "05 - a.JPG"), generate(files, numbering = true, width = 2, start = "0004", numberFirst = true))
        assertEquals(listOf("z - 9999.JPG", "a - 10000.JPG"), generate(files, numbering = true, width = 4, start = "9999"))
    }

    @Test fun `dotfiles and dotted directories keep their full names and extension-only skips directories`() {
        val files = listOf(file(".old"), file("old.dir", true), file("old"))
        assertEquals(listOf(".new", "new.dir", "new"), generate(files))
        assertEquals(listOf(".old", "old.dir", "old"), generate(files, BatchRenameNames.Part.EXTENSION))
        listOf("", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { assertFalse(BatchRenameNames.valid(it)) }
    }

    /** Opaque handles ensure no path slicing can accidentally pass a parent or conflict check. */
    private class WritableFs : FileSystem by FakeFileSystem("batch", emptyMap()) {
        val parent = XFile("batch", "content://tree/document/root%2Ffolder", true, displayName = "folder")
        var entries = listOf("old-a.txt", "old-b.txt", "taken.txt").mapIndexed { i, name ->
            XFile("batch", "content://tree/document/item$i", false, displayName = name)
        }
        val renamed = mutableListOf<Pair<XFile, String>>()
        var failListing = false
        var failSecond = false
        override fun writable() = true
        override fun list(dir: XFile): List<XFile> {
            check(dir == parent)
            check(!failListing) { "listing failed" }
            return entries
        }
        override fun parentOf(file: XFile): XFile? = null
        override fun rename(file: XFile, newName: String): XFile {
            check(!failSecond || renamed.isEmpty()) { "connection lost" }
            renamed += file to newName
            return file.copy(displayName = newName)
        }
    }

    private fun withFs(block: (WritableFs) -> Unit) {
        val fs = WritableFs()
        FsRegistry.register(fs)
        try { block(fs) } finally { FsRegistry.unregister(fs.scheme) }
    }

    @Test fun `all conflicts and failed listings are rejected before any rename`() = withFs { fs ->
        val files = fs.entries.take(2)
        val parents = files.associateWith { fs.parent }
        for (names in listOf(listOf("safe.txt", "TAKEN.txt"), listOf("same.txt", "same.txt"))) {
            assertTrue(runCatching { BatchRenameDialog.execute(context, files, names, parents) {} }.isFailure)
            assertTrue(fs.renamed.isEmpty())
        }
        fs.failListing = true
        assertTrue(runCatching { BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt"), parents) {} }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }

    @Test fun `execution uses preview names with opaque handles and reports partial completion`() = withFs { fs ->
        val files = fs.entries.take(2)
        val names = generate(files, numbering = true, width = 3, start = "4")
        fs.failSecond = true
        var completed = 0
        val result = runCatching { BatchRenameDialog.execute(context, files, names, files.associateWith { fs.parent }) { completed++ } }
        assertTrue(result.isFailure)
        assertEquals(1, completed)
        assertEquals(listOf(files[0] to "old-a - 004.txt"), fs.renamed)
    }

    @Test fun `successful batch uses both preview names and skips unchanged entries`() = withFs { fs ->
        val files = fs.entries
        var completed = 0
        BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt", "taken.txt"), files.associateWith { fs.parent }) { completed++ }
        assertEquals(2, completed)
        assertEquals(listOf(files[0] to "a.txt", files[1] to "b.txt"), fs.renamed)
    }

    @Test fun `readonly selection is rejected before mutating any writable entries`() = withFs { fs ->
        val files = fs.entries.take(2).mapIndexed { i, file -> if (i == 1) file.copy(canWrite = false) else file }
        assertTrue(runCatching {
            BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt"), files.associateWith { fs.parent }) {}
        }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }

    @Test fun `selected ancestor is rejected even across an unselected middle folder`() = withFs { fs ->
        val ancestor = XFile("batch", "opaque:grandparent", true, displayName = "ancestor")
        val root = XFile("batch", "opaque:root", true)
        val files = listOf(ancestor, fs.entries.first())
        // A second backend view supplies listings for the ancestor's parent too.
        val original = fs
        FsRegistry.register(object : FileSystem by original {
            override fun list(dir: XFile) = if (dir == root) listOf(ancestor) else original.list(dir)
        })
        val parents = mapOf(files[1] to fs.parent, fs.parent to ancestor, ancestor to root)
        assertTrue(runCatching { BatchRenameDialog.execute(context, files, listOf("renamed", "new.txt"), parents) {} }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }
}
