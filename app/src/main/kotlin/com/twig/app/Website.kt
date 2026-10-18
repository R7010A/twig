package com.twig.app

import android.content.Context
import com.twig.app.secure.Secrets
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A bookmark is an action in the tree, rather than a filesystem directory. */
data class Website(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    /** Legacy field: now only controls the toolbar; old records hid all three bars. */
    val fullscreen: Boolean = false,
    val pullToRefresh: Boolean = false,
    val doubleTapToolbar: Boolean = true,
    val hideStatusBar: Boolean = fullscreen,
    val hideNavigationBar: Boolean = fullscreen,
    val disableTextSelection: Boolean = false,
    val lockScrollAxis: Boolean = false,
    val desktopSite: Boolean = false,
    /** Open in immersive display without adding an application Back-navigation step. */
    val openFullscreen: Boolean = false,
    val orientation: WebsiteOrientation = WebsiteOrientation.SYSTEM,
    val script: String = "",
    val style: String = "",
    /** One CSS selector per line; applied independently from custom CSS. */
    val hiddenElements: String = "",
    /** One domain or HTTP(S) URL pattern per line. */
    val blockedRequests: String = "",
    val userAgent: String = "",
    /** One Name: value per line; kept decrypted only in memory. */
    val headers: String = "",
    val basicAuthUsername: String = "",
    val basicAuthPassword: String = "",
)

enum class WebsiteOrientation(val key: String) {
    SYSTEM("system"), FREE("free"), LOCKED("locked"), LANDSCAPE("landscape"), PORTRAIT("portrait");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object WebsiteStore {
    fun preferences(ctx: Context) = ctx.getSharedPreferences("twig_websites", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Website> {
        val raw = preferences(ctx).getString("list", null) ?: return emptyList()
        return parse(raw, { value -> runCatching { Secrets.dec(ctx, value) }.getOrDefault(value) })
    }

    private fun parse(raw: String, openSecret: (String) -> String, strict: Boolean = false): List<Website> {
        val parsed = runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).map { index ->
                val o = items.getJSONObject(index)
                val fullscreen = o.optBoolean("fullscreen")
                Website(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                    fullscreen = fullscreen, pullToRefresh = o.optBoolean("pullToRefresh"),
                    doubleTapToolbar = o.optBoolean("doubleTapToolbar", true),
                    hideStatusBar = o.optBoolean("hideStatusBar", fullscreen),
                    hideNavigationBar = o.optBoolean("hideNavigationBar", fullscreen),
                    disableTextSelection = o.optBoolean("disableTextSelection"),
                    lockScrollAxis = o.optBoolean("lockScrollAxis"), desktopSite = o.optBoolean("desktopSite"),
                    openFullscreen = o.optBoolean("openFullscreen"),
                    orientation = WebsiteOrientation.fromKey(o.optString("orientation")),
                    script = o.optString("script"), style = o.optString("style"),
                    hiddenElements = o.optString("hiddenElements"), blockedRequests = o.optString("blockedRequests"),
                    userAgent = o.optString("userAgent"), headers = openSecret(o.optString("headers")),
                    basicAuthUsername = openSecret(o.optString("basicAuthUsername")),
                    basicAuthPassword = openSecret(o.optString("basicAuthPassword")),
                )
            }
        }
        return if (strict) parsed.getOrThrow() else parsed.getOrDefault(emptyList())
    }

    fun save(ctx: Context, site: Website) {
        val items = all(ctx).toMutableList()
        val index = items.indexOfFirst { it.id == site.id }
        if (index < 0) items.add(site) else items[index] = site
        persist(ctx, items)
    }

    fun remove(ctx: Context, id: String) = persist(ctx, all(ctx).filter { it.id != id })

    /** Backups carry plaintext fields under their own optional password encryption. */
    fun exportJson(ctx: Context): String {
        val sites = all(ctx)
        if (sites.any { site ->
            listOf(site.headers, site.basicAuthUsername, site.basicAuthPassword).any { it.startsWith("enc1:") }
        }) throw Secrets.KeyUnavailable()
        return json(sites) { it }.toString()
    }

    fun importJson(ctx: Context, raw: String) = persist(ctx, parse(raw, { it }, strict = true))

    private fun persist(ctx: Context, sites: List<Website>) {
        val items = json(sites) {
            // A locked reader may carry existing ciphertext. Never encrypt it twice.
            if (it.startsWith("enc1:")) it else Secrets.enc(ctx, it)
        }
        preferences(ctx).edit().putString("list", items.toString()).apply()
    }

    private fun json(sites: List<Website>, sealSecret: (String) -> String): JSONArray {
        val items = JSONArray()
        sites.forEach {
            items.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("url", it.url).put("fullscreen", it.fullscreen)
                .put("doubleTapToolbar", it.doubleTapToolbar).put("pullToRefresh", it.pullToRefresh).put("hideStatusBar", it.hideStatusBar)
                .put("hideNavigationBar", it.hideNavigationBar).put("disableTextSelection", it.disableTextSelection)
                .put("lockScrollAxis", it.lockScrollAxis).put("desktopSite", it.desktopSite)
                .put("openFullscreen", it.openFullscreen).put("orientation", it.orientation.key)
                .put("script", it.script).put("style", it.style).put("hiddenElements", it.hiddenElements).put("userAgent", it.userAgent)
                .put("blockedRequests", it.blockedRequests)
                .put("headers", sealSecret(it.headers))
                .put("basicAuthUsername", sealSecret(it.basicAuthUsername))
                .put("basicAuthPassword", sealSecret(it.basicAuthPassword)))
        }
        return items
    }

    /** Bare hosts use HTTPS; never accept local-file or executable URLs. */
    fun normalizeUrl(input: String): String? = runCatching {
        val text = input.trim()
        if (text.isEmpty()) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        val uri = URI(candidate)
        if (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65535)) return null
        uri.toASCIIString()
    }.getOrNull()
}
