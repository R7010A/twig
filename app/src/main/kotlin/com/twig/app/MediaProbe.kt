package com.twig.app

import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Properties probing has a shared byte/time budget, independent of playback. */
internal class MediaProbe(
    maxBytes: Long = 16L * 1024 * 1024,
    timeoutMs: Long = 10_000,
    private val clock: () -> Long = System::nanoTime,
) {
    private val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private var remaining = maxBytes
    @Volatile private var stopped = false

    fun remainingMs(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - clock()).coerceAtLeast(0)

    fun stop() { stopped = true }

    @Synchronized private fun reserve(length: Int): Int {
        if (stopped || clock() >= deadline || remaining <= 0) throw IOException("Media probe limit reached")
        return minOf(length.toLong(), remaining).toInt().also { remaining -= it }
    }

    fun wrap(source: RandomSource): RandomSource = object : RandomSource {
        override fun length() = source.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val allowed = reserve(length)
            return source.readAt(position, buffer, offset, allowed)
        }
        override fun close() = source.close()
    }

    fun open(file: XFile): RandomSource {
        val fs = FsRegistry.of(file)
        // Reopening and skipping/decompressing to an offset cannot obey a byte-read budget.
        val efficient = (fs as? ArchiveFileSystem)?.fastRandom(file) ?: fs.randomAccessEfficient()
        if (!efficient) throw IOException("Media probing requires efficient random access")
        if (stopped || remainingMs() <= 0) throw IOException("Media probe stopped")
        // EBML headers are read a few bytes at a time; coalesce them without unbounded prefetch.
        val cached = com.twig.app.ui.BufferedRandomSource(wrap(fs.openRandom(file)),
            block = 64 * 1024, maxBlocks = 4, ahead = 0)
        return object : RandomSource by cached {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                // Cache hits must stop too, or a malformed parser can loop on the same bytes forever.
                if (stopped || remainingMs() <= 0) throw IOException("Media probe stopped")
                return cached.readAt(position, buffer, offset, length)
            }
        }
    }

    /** Native metadata calls cannot be interrupted. Bound their wait and the number of stuck workers. */
    fun run(block: () -> Unit) {
        val task = FutureTask { block() }
        try {
            workers.execute(task)
            task.get(remainingMs(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // The caller retains any rows collected before failure or timeout.
        } finally {
            stop()
            task.cancel(true)
        }
    }

    companion object {
        private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "twig-media-info").apply { isDaemon = true } })
    }
}
