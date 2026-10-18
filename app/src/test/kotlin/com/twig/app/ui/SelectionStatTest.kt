package com.twig.app.ui

import com.twig.core.FsRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionStatTest {
    @Test
    fun `overlapping selections count each entry once and preserve similarly named siblings`() = runBlocking {
        val fs = FakeFileSystem("selection-stat", mapOf(
            "/a" to listOf("sub", "one"), "/a/sub" to listOf("two"),
        ), mapOf("/a/one" to "123", "/a/sub/two" to "12345", "/ab" to "12"))
        FsRegistry.register(fs)
        try {
            val selected = listOf("/a/sub/two", "/ab", "/a", "/a/sub", "/a/one", "/ab").map(fs::resolve)
            val result = scanSelectionStat(selected).toList().last()
            assertEquals(DirStat(files = 3, dirs = 2, bytes = 10), result)
            assertEquals(listOf("/a", "/a/sub"), fs.listed)
            assertTrue(fs.opened.isEmpty())
        } finally {
            FsRegistry.unregister(fs.scheme)
        }
    }

    @Test
    fun `same paths from different sources are independent`() = runBlocking {
        val a = FakeFileSystem("selection-a", mapOf("/" to listOf("one")), mapOf("/one" to "123"))
        val b = FakeFileSystem("selection-b", emptyMap(), mapOf("/one" to "12345"))
        FsRegistry.register(a)
        FsRegistry.register(b)
        try {
            assertEquals(DirStat(files = 2, dirs = 1, bytes = 8),
                scanSelectionStat(listOf(a.root(), b.resolve("/one"))).toList().last())
        } finally {
            FsRegistry.unregister(a.scheme)
            FsRegistry.unregister(b.scheme)
        }
    }
}
