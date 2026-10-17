package com.twig.app.ui

import android.content.Context
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.twig.app.R
import com.twig.app.ServerCertificateTrust
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.SafeWrite
import com.twig.core.XFile
import com.twig.core.isWritableDir
import com.twig.fs.network.ServerTls
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

/** In-memory download inputs: cookies and credentials never enter an Intent or persisted job. */
internal class WebsiteDownloadTask(
    val site: Website,
    val url: String,
    val userAgent: String,
    val name: String,
    val size: Long,
) {
    private val call = AtomicReference<Call?>()
    private val cancelled = AtomicBoolean()

    fun cancel() {
        cancelled.set(true)
        call.get()?.cancel()
    }

    fun execute(
        ctx: Context,
        destination: XFile,
        listener: CopyEngine.ProgressListener,
        isCancelled: () -> Boolean,
        cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
        acceptCookies: (String, String) -> Unit = { target, value -> CookieManager.getInstance().setCookie(target, value) },
    ) {
        fun stopped() = cancelled.get() || isCancelled()
        fun checkCancelled() { if (stopped()) throw IOException(ctx.getString(R.string.dialog_cancel)) }
        checkCancelled()
        require(validName(name)) { ctx.getString(R.string.website_download_invalid_name) }
        require(WebsiteStore.normalizeUrl(url) == url) { ctx.getString(R.string.website_download_unsupported) }
        val client = ServerTls.configure(OkHttpClient.Builder(), ServerCertificateTrust.forApp(ctx))
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            var target = url
            // Rebuild every request: additional headers and Basic Auth belong only to the bookmark origin.
            for (redirect in 0..10) {
                checkCancelled()
                val builder = Request.Builder().url(target)
                if (WebsiteCustomization.validUserAgent(userAgent) && userAgent.isNotBlank()) builder.header("User-Agent", userAgent)
                WebsiteCustomization.headersFor(site, target).forEach { (key, value) -> builder.header(key, value) }
                // Even an explicitly configured Cookie header must not be carried onto another origin.
                if (builder.build().header("Cookie") == null) cookies(target)?.let { builder.header("Cookie", it) }
                if (builder.build().header("Authorization") == null) {
                    WebsiteCustomization.basicAuthFor(site, target, java.net.URI(target).host.orEmpty())?.let {
                        builder.header("Authorization", Credentials.basic(it.first, it.second))
                    }
                }
                val current = client.newCall(builder.build())
                call.set(current)
                if (stopped()) current.cancel()
                current.execute().use { response ->
                    checkCancelled()
                    response.headers("Set-Cookie").forEach { acceptCookies(target, it) }
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (redirect == 10) throw IOException(ctx.getString(R.string.website_download_redirects))
                        val next = response.header("Location")?.let { response.request.url.resolve(it) }
                            ?: throw IOException(ctx.getString(R.string.website_download_redirects))
                        // Never downgrade an authenticated HTTPS download through a redirect.
                        if (response.request.url.isHttps && !next.isHttps) throw IOException(ctx.getString(R.string.website_download_redirects))
                        target = next.toString()
                    } else {
                        if (!response.isSuccessful || response.code == 206) {
                            throw IOException(ctx.getString(R.string.website_download_http_error, response.code))
                        }
                        val body = response.body ?: throw IOException(ctx.getString(R.string.err_failed))
                        val total = body.contentLength().coerceAtLeast(0)
                        checkCancelled()
                        val dir = prepareDestination(ctx, destination)
                        val fs = FsRegistry.of(dir)
                        require(dir.isWritableDir()) { ctx.getString(R.string.msg_pick_dir_first) }
                        // A failed listing is unknown, never permission to overwrite an existing file.
                        val taken = fs.list(dir).map { it.name }.toSet()
                        val free = freeName(name, taken)
                        val source = XFile("website-download", "/$free", false, total)
                        listener.onFile(source)
                        body.byteStream().use { input ->
                            SafeWrite.replace(fs, dir, free, null) { output ->
                                var copied = 0L
                                val done = CopyEngine.pipe(input, output, { stopped() }) { n ->
                                    copied += n
                                    listener.onFileBytes(copied, total)
                                    listener.onBytes(copied, total)
                                }
                                checkCancelled()
                                if (!done || (total > 0 && copied != total)) throw IOException(ctx.getString(R.string.website_download_incomplete))
                            }
                        }
                        listener.onItemDone(false)
                        listener.onDone()
                        return
                    }
                }
                call.set(null)
            }
        } finally {
            call.getAndSet(null)?.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun defaultDirectory(): XFile = XFile("file",
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "twig").absolutePath, true)

        private fun prepareDestination(ctx: Context, dir: XFile): XFile {
            if (dir == defaultDirectory()) {
                val folder = File(dir.path)
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException(ctx.getString(R.string.err_failed) + ": " + dir.path)
            }
            return dir
        }

        fun validName(value: String) = value.isNotBlank() && value !in setOf(".", "..") &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

        fun suggestedName(url: String, disposition: String?, mime: String?): String {
            val extended = disposition?.let { Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)").find(it)?.groupValues?.get(1) }
            val raw = extended?.let { runCatching { URLDecoder.decode(it.trim().trim('"').replace("+", "%2B"), "UTF-8") }.getOrNull() }
                ?: URLUtil.guessFileName(url, disposition, mime)
            val clean = raw.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it.code != 127 }.trim()
            return clean.takeIf { validName(it) } ?: "download"
        }

        fun freeName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot)
            val ext = name.substring(dot)
            var index = 1
            while ("$base ($index)$ext" in taken) index++
            return "$base ($index)$ext"
        }
    }
}
