package com.twig.app.ui

import com.twig.core.XFile
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveformLoaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val cache = Queue()
    private val decode = Queue()
    private val main = Queue()
    private val full = byteArrayOf(8, 80, 120, -1)
    private var computations = 0
    private lateinit var loader: WaveformLoader
    private lateinit var directory: File
    private var work: (XFile, () -> Boolean) -> WaveformResult? = { _, _ ->
        WaveformResult(full, true)
    }
    private fun setup() {
        directory = temporary.newFolder()
        loader = WaveformLoader(4, cache, decode, { main.execute(it) }) { file, alive ->
            computations++
            work(file, alive)
        }
    }
    private fun request(key: String, prefetch: Boolean = false, ready: (ByteArray) -> Unit = {}) =
        loader.request(key, directory, XFile("file", "/$key.mp3", false), prefetch, ready)

    @Test fun `disk cache bypasses pending decode and memory bypasses disk queue`() {
        setup()
        request("uncached")
        cache.drain()
        File(directory, "cached").writeBytes(full)
        var delivered = 0
        request("cached") { delivered++ }
        cache.drain(); main.drain()
        assertEquals(1, delivered)
        assertEquals(0, computations)
        assertEquals(1, decode.tasks.size)
        request("cached") { delivered++ }
        main.drain()
        assertEquals(2, delivered)
        assertTrue(cache.tasks.isEmpty())
    }

    @Test fun `same song foreground and prefetch decode once and reach both listeners`() {
        setup()
        var foreground = 0
        var prefetch = 0
        request("song") { foreground++ }
        request("song", true) { prefetch++ }
        cache.drain()
        assertEquals(1, decode.tasks.size)
        decode.drain(); main.drain()
        assertEquals(1, computations)
        assertEquals(1, foreground)
        assertEquals(1, prefetch)
        assertArrayEquals(full, File(directory, "song").readBytes())
    }

    @Test fun `next song prefetch does not cancel current foreground`() {
        setup()
        work = { file, alive ->
            if (file.name == "current.mp3") {
                request("next", true)
                cache.drain()
                assertTrue(alive())
            }
            WaveformResult(full, true)
        }
        val snapshots = mutableListOf<ByteArray>()
        request("current") { snapshots.add(it) }
        cache.drain(); decode.drain(); main.drain()
        assertEquals(2, computations)
        assertEquals(1, snapshots.size)
        assertArrayEquals(full, snapshots.last())
    }

    @Test fun `foreground adopts running prefetch without restarting decode`() {
        setup()
        var delivered = 0
        work = { _, alive ->
            request("song") { delivered++ }
            cache.drain()
            assertTrue(alive())
            WaveformResult(full, true)
        }
        request("song", true)
        cache.drain(); decode.drain(); main.drain()
        assertEquals(1, computations)
        assertEquals(1, delivered)
    }

    @Test fun `running prefetch yields on switch to different foreground`() {
        setup()
        work = { file, alive ->
            if (file.name == "old.mp3") {
                request("new")
                cache.drain()
                assertFalse(alive())
                null
            } else WaveformResult(full, true)
        }
        request("old", true)
        cache.drain(); decode.drain(); main.drain()
        assertEquals(2, computations)
        assertFalse(File(directory, "old").exists())
        assertTrue(File(directory, "new").exists())
    }

    @Test fun `queued callbacks exclude obsolete listener even for same song`() {
        setup()
        var old = 0
        var current = 0
        request("song") { old++ }
        cache.drain(); decode.drain()
        request("song") { current++ }
        main.drain()
        assertEquals(0, old)
        assertEquals(1, current)
        loader.cancel()
        request("song") { current++ }
        loader.cancel()
        main.drain()
        assertEquals(1, current)
    }

    @Test fun `foreground receives nothing until full analysis returns`() {
        setup()
        var delivered = 0
        work = { _, alive ->
            main.drain()
            assertTrue(alive())
            assertEquals(0, delivered)
            WaveformResult(full, true)
        }
        request("song") { delivered++ }
        cache.drain(); decode.drain(); main.drain()
        assertEquals(1, delivered)
    }

    @Test fun `partial results display but are never retained in either cache`() {
        setup()
        val partial = byteArrayOf(80, 0, 0, 0)
        work = { _, _ -> WaveformResult(partial, false) }
        val seen = mutableListOf<ByteArray>()
        request("song") { seen.add(it) }
        cache.drain(); decode.drain(); main.drain()
        assertArrayEquals(partial, seen.first())
        assertFalse(File(directory, "song").exists())
        request("song")
        cache.drain(); decode.drain()
        assertEquals(2, computations)
    }
}
