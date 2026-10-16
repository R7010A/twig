package com.twig.app

import android.content.Context
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
    val hideStatusBar: Boolean = fullscreen,
    val hideNavigationBar: Boolean = fullscreen,
    val disableTextSelection: Boolean = false,
    val lockScrollAxis: Boolean = false,
    val desktopSite: Boolean = false,
)

object WebsiteStore {
    fun preferences(ctx: Context) = ctx.getSharedPreferences("twig_websites", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Website> {
        val raw = preferences(ctx).getString("list", null) ?: return emptyList()
        return runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).map { index ->
                val o = items.getJSONObject(index)
                val fullscreen = o.optBoolean("fullscreen")
                Website(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                    fullscreen = fullscreen, pullToRefresh = o.optBoolean("pullToRefresh"),
                    hideStatusBar = o.optBoolean("hideStatusBar", fullscreen),
                    hideNavigationBar = o.optBoolean("hideNavigationBar", fullscreen),
                    disableTextSelection = o.optBoolean("disableTextSelection"),
                    lockScrollAxis = o.optBoolean("lockScrollAxis"), desktopSite = o.optBoolean("desktopSite"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, site: Website) {
        val items = all(ctx).toMutableList()
        val index = items.indexOfFirst { it.id == site.id }
        if (index < 0) items.add(site) else items[index] = site
        persist(ctx, items)
    }

    fun remove(ctx: Context, id: String) = persist(ctx, all(ctx).filter { it.id != id })

    private fun persist(ctx: Context, sites: List<Website>) {
        val items = JSONArray()
        sites.forEach {
            items.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("url", it.url).put("fullscreen", it.fullscreen)
                .put("pullToRefresh", it.pullToRefresh).put("hideStatusBar", it.hideStatusBar)
                .put("hideNavigationBar", it.hideNavigationBar).put("disableTextSelection", it.disableTextSelection)
                .put("lockScrollAxis", it.lockScrollAxis).put("desktopSite", it.desktopSite))
        }
        preferences(ctx).edit().putString("list", items.toString()).apply()
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
