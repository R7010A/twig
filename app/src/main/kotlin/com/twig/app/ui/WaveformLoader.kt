package com.twig.app.ui

import com.twig.core.XFile
import java.io.File
import java.util.concurrent.Executor

internal class WaveformResult(val data: ByteArray, val complete: Boolean)

/** Cache reads never queue behind decoding. Foreground and prefetch share one job
 * per song; callbacks are checked again at delivery to exclude obsolete screens. */
internal class WaveformLoader(
    private val buckets: Int,
    private val cacheExecutor: Executor,
    private val decoderExecutor: Executor,
    private val deliver: (() -> Unit) -> Unit,
    private val compute: (XFile, () -> Boolean) -> WaveformResult?,
) {
    private class Listener(val key: String, var callback: ((ByteArray) -> Unit)?)
    private class Job(val key: String, val sequence: Long)
    private val lock = Any()
    private var foreground: Listener? = null
    private var prefetch: Listener? = null
    private var sequence = 0L
    private val jobs = HashMap<String, Job>()
    private val memory = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 20
    }

    fun request(key: String, directory: File, file: XFile, isPrefetch: Boolean, callback: (ByteArray) -> Unit) {
        val listener = Listener(key, callback)
        val cached = synchronized(lock) {
            if (isPrefetch) prefetch = listener else {
                if (foreground?.key != key) sequence++
                foreground = listener
            }
            memory[key]
        }
        if (cached != null) { notify(listener, cached); return }
        cacheExecutor.execute cacheRead@{
            val cache = File(directory, key)
            val disk = runCatching { cache.readBytes() }.getOrNull()?.takeIf { it.size == buckets }
            synchronized(lock) {
                if (!current(listener)) return@cacheRead
                val hit = memory[key] ?: disk
                if (hit != null) {
                    memory[key] = hit
                    notify(listener, hit)
                    return@cacheRead
                }
                if (jobs.containsKey(key)) return@cacheRead
                val job = Job(key, sequence)
                jobs[key] = job
                decoderExecutor.execute decode@{
                    val alive = { synchronized(lock) {
                        val wanted = jobs[key] === job &&
                            (foreground?.key == key || (prefetch?.key == key && sequence == job.sequence))
                        // A request arriving after cancellation needs a fresh job.
                        if (!wanted && jobs[key] === job) jobs.remove(key)
                        wanted
                    } }
                    try {
                        if (!alive()) return@decode
                        val wave = runCatching { compute(file, alive) }.getOrNull()
                        if (wave != null && alive()) {
                            if (wave.complete) {
                                synchronized(lock) { memory[key] = wave.data }
                                runCatching { directory.mkdirs(); cache.writeBytes(wave.data) }
                            }
                            publish(job, wave.data)
                        }
                    } finally {
                        synchronized(lock) {
                            if (jobs[key] === job) {
                                jobs.remove(key)
                                val listeners = listOfNotNull(foreground, prefetch).filter { it.key == key }
                                // Release Activity callbacks after queued final delivery.
                                deliver { synchronized(lock) { listeners.forEach { it.callback = null } } }
                            }
                        }
                    }
                }
            }
        }
    }

    fun cancel() = synchronized(lock) { foreground = null; prefetch = null; sequence++ }
    private fun current(listener: Listener): Boolean = foreground === listener || prefetch === listener
    private fun notify(listener: Listener, data: ByteArray) = deliver {
        val callback = synchronized(lock) { if (current(listener)) listener.callback else null }
        callback?.invoke(data)
    }
    private fun publish(job: Job, data: ByteArray) = synchronized(lock) {
        if (jobs[job.key] !== job) return@synchronized
        foreground?.takeIf { it.key == job.key }?.let { notify(it, data) }
        prefetch?.takeIf { it.key == job.key }?.let { notify(it, data) }
    }
}
