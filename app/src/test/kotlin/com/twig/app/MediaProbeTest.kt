package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaProbeTest {
    private class Source : RandomSource {
        var read = 0
        var closed = false
        override fun length() = Long.MAX_VALUE
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            read += length
            return length
        }
        override fun close() { closed = true }
    }

    @Test fun `byte budget covers repeated reads and all opened sources`() {
        val probe = MediaProbe(maxBytes = 10)
        val a = Source()
        val b = Source()
        val buffer = ByteArray(20)
        probe.wrap(a).use { first ->
            assertEquals(7, first.readAt(500, buffer, 0, 7))
            probe.wrap(b).use { second ->
                assertEquals(3, second.readAt(0, buffer, 0, 20))
                assertThrows(IOException::class.java) { first.readAt(500, buffer, 0, 1) }
            }
        }
        assertEquals(10, a.read + b.read)
        assertTrue(a.closed && b.closed)
    }

    @Test fun `expired and stopped probes refuse further source reads`() {
        var now = 0L
        val source = Source()
        val probe = MediaProbe(timeoutMs = 1, clock = { now })
        val wrapped = probe.wrap(source)
        now = 1_000_000
        assertThrows(IOException::class.java) { wrapped.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(0, source.read)
        val stopped = MediaProbe().apply { stop() }.wrap(source)
        assertThrows(IOException::class.java) { stopped.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(0, source.read)
    }

    @Test fun `caller stops waiting even when a native task ignores interruption`() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val probe = MediaProbe(timeoutMs = 100)
        val source = Source()
        val wrapped = probe.wrap(source)
        val before = System.nanoTime()
        try {
            probe.run {
                try {
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    assertThrows(IOException::class.java) { wrapped.readAt(0, ByteArray(1), 0, 1) }
                } finally { wrapped.close(); finished.countDown() }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2_000)
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, source.read)
        assertTrue(source.closed)
    }

    @Test fun `media headers remain readable when the cues index is corrupt`() {
        // Generated 16x16 VP8 black frame: ffmpeg -f lavfi -i color=size=16x16:rate=1 -t 1 -c:v libvpx.
        val bytes = javaClass.getResourceAsStream("/media-probe.mkv")!!.use { it.readBytes() }
        val cues = bytes.indices.last { i -> i + 4 <= bytes.size &&
            bytes.copyOfRange(i, i + 4).contentEquals(byteArrayOf(0x1c, 0x53, 0xbb.toByte(), 0x6b)) }
        bytes.fill(0, cues, bytes.size)
        val scheme = "probe-test"
        var read = 0
        var readCalls = 0
        var closed = 0
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = scheme
            override fun randomAccessEfficient() = true
            override fun openRandom(file: XFile): RandomSource = object : RandomSource {
                override fun length() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    readCalls++
                    if (position >= bytes.size) return -1
                    val n = minOf(length, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    read += n
                    return n
                }
                override fun close() { closed++ }
            }
        })
        try {
            val file = XFile(scheme, "/clip.mkv", false, size = bytes.size.toLong())
            val probe = MediaProbe()
            probe.open(file).use { source ->
                val one = ByteArray(1)
                repeat(100) { source.readAt(it.toLong(), one, 0, 1) }
                probe.stop()
                assertThrows(IOException::class.java) { source.readAt(0, one, 0, 1) }
            }
            assertTrue("small header reads are coalesced", readCalls <= 2)
            val app = ApplicationProvider.getApplicationContext<Application>()
            val details = FileInfo.load(app, file)
            val media = details.sections.single { it.title == app.getString(R.string.info_section_media) }
            assertTrue(media.rows.any { it.second.contains("VP8") && it.second.contains("16×16") })
            assertTrue(media.rows.any { it.first == app.getString(R.string.info_duration) && it.second == "0:01" })
            assertTrue(read <= 16 * 1024 * 1024)
            assertTrue(closed > 0)
        } finally { FsRegistry.unregister(scheme) }
    }
}
