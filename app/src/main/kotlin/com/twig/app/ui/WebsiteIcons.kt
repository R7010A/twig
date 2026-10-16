package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Browser-provided favicons only: loading a tree never fetches a website. */
object WebsiteIcons {
    private const val MAX_SIDE = 256
    private const val MAX_BYTES = 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = max(1, value.allocationByteCount / 1024)
    }
    // Main-thread only. A recycled row's normal icon tag invalidates its old binding.
    private val bound = WeakHashMap<ImageView, String>()
    internal var io: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WebsiteIcons").apply { isDaemon = true }
    }

    private fun key(site: Website): String = MessageDigest.getInstance("SHA-256")
        .digest((site.id + "\n" + site.url).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun file(ctx: Context, site: Website) = File(ctx.cacheDir, "website-icons/${key(site)}.png")
    private fun current(ctx: Context, site: Website) =
        WebsiteStore.all(ctx).firstOrNull { it.id == site.id && it.url == site.url }

    fun cached(site: Website): Bitmap? = memory.get(key(site))

    fun bind(view: ImageView, site: Website) {
        val tag = "website-icon:${key(site)}"
        view.tag = tag
        bound[view] = tag
        val bitmap = cached(site)
        if (bitmap != null) view.setImageBitmap(bitmap) else {
            view.setImageResource(R.drawable.ic_website)
            load(view.context, site) { loaded ->
                if (loaded != null && view.tag == tag) view.setImageBitmap(loaded)
            }
        }
    }

    /** Callbacks run on main; disk work never runs on a row bind or pin request. */
    fun load(ctx: Context, site: Website, result: (Bitmap?) -> Unit) {
        cached(site)?.let { result(it); return }
        val app = ctx.applicationContext
        io.execute {
            val bitmap = cached(site) ?: read(app, site)?.also { memory.put(key(site), it) }
            main.post { result(cached(site) ?: bitmap) }
        }
    }

    private fun read(ctx: Context, site: Website): Bitmap? = runCatching {
        val source = file(ctx, site)
        if (!source.isFile || source.length() > MAX_BYTES) return null
        val data = source.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..MAX_SIDE || bounds.outHeight !in 1..MAX_SIDE) return null
        BitmapFactory.decodeByteArray(data, 0, data.size)
    }.getOrNull()

    /** Ignore icons from links to unrelated sites while browsing this bookmark. */
    internal fun matchesPage(site: Website, pageUrl: String?): Boolean {
        if (pageUrl == null) return false
        val entry = Uri.parse(site.url)
        val page = Uri.parse(pageUrl)
        fun host(uri: Uri) = uri.host?.lowercase()?.removePrefix("www.")
        fun port(uri: Uri) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        if (page.scheme !in setOf("http", "https") || host(entry) == null || host(entry) != host(page)) return false
        return entry.scheme == page.scheme && port(entry) == port(page) ||
            entry.scheme == "http" && page.scheme == "https" && port(entry) == 80 && port(page) == 443
    }

    fun received(ctx: Context, site: Website, pageUrl: String?, icon: Bitmap) {
        if (!matchesPage(site, pageUrl) || icon.isRecycled) return
        val app = ctx.applicationContext
        io.execute {
            if (current(app, site) == null || icon.isRecycled) return@execute
            try {
                val side = min(MAX_SIDE, max(icon.width, icon.height))
                if (side <= 0) return@execute
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val scale = min(side.toFloat() / icon.width, side.toFloat() / icon.height)
                val width = icon.width * scale
                val height = icon.height * scale
                Canvas(bitmap).drawBitmap(icon, null,
                    RectF((side - width) / 2, (side - height) / 2, (side + width) / 2, (side + height) / 2),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                val bytes = ByteArrayOutputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return@execute
                    out.toByteArray()
                }
                val target = file(app, site)
                val changed = !target.isFile || target.length() != bytes.size.toLong() ||
                    !target.readBytes().contentEquals(bytes)
                if (changed) {
                    target.parentFile?.mkdirs()
                    val atomic = AtomicFile(target)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(bytes)
                        atomic.finishWrite(stream)
                    } catch (e: Exception) {
                        atomic.failWrite(stream)
                        throw e
                    }
                }
                memory.put(key(site), bitmap)
                main.post {
                    val saved = current(app, site) ?: return@post
                    val tag = "website-icon:${key(site)}"
                    val iterator = bound.entries.iterator()
                    while (iterator.hasNext()) {
                        val binding = iterator.next()
                        if (binding.key.tag != binding.value) iterator.remove()
                        else if (binding.value == tag) binding.key.setImageBitmap(bitmap)
                    }
                    if (changed) WebsiteDialogs.updateShortcut(app, saved)
                }
            } catch (e: Exception) {
                Log.w("WebsiteIcons", "Could not cache website icon", e)
            }
        }
    }

    fun forget(ctx: Context, site: Website) {
        memory.remove(key(site))
        val app = ctx.applicationContext
        io.execute { AtomicFile(file(app, site)).delete() }
    }

    internal fun clearMemory() {
        memory.evictAll()
        bound.clear()
    }
}
