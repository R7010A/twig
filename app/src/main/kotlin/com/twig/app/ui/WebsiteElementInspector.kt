package com.twig.app.ui

import androidx.core.content.ContextCompat
import com.twig.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** No JavaScript interface: only explicit main-frame evaluations return element metadata. */
internal class WebsiteElementInspector(
    private val web: WebsiteWebView,
    private val selected: (Selection?) -> Unit,
) {
    data class Selection(val tag: String, val id: String, val classes: String, val selector: String,
        val matches: Int, val html: String, val truncated: Boolean, val hasParent: Boolean)

    private val key = "__twig_inspector_${UUID.randomUUID()}"
    private val source by lazy { web.context.assets.open("website_inspect.js").bufferedReader().use { it.readText() } }
    private var generation = 0
    private var injected = false

    internal fun command(action: String, x: Float = 0f, y: Float = 0f): String {
        val color = String.format(Locale.ROOT, "#%06X", ContextCompat.getColor(web.context, R.color.primary) and 0xffffff)
        val args = JSONArray().put(key).put(action).put(x.toDouble()).put(y.toDouble()).put(color).toString()
        return "($source)(" + args.substring(1, args.length - 1) + ")"
    }

    fun pick(x: Float, y: Float) = evaluate("pick", x, y)
    fun parent() = evaluate("parent")

    private fun evaluate(action: String, x: Float = 0f, y: Float = 0f) {
        val serial = ++generation
        injected = true
        web.evaluateJavascript(command(action, x, y)) { raw ->
            if (serial != generation) return@evaluateJavascript
            val value = runCatching {
                val o = JSONObject(raw ?: "null")
                Selection(o.getString("tag"), o.getString("id"), o.getString("classes"), o.getString("selector"),
                    o.getInt("matches"), o.getString("html"), o.getBoolean("truncated"), o.getBoolean("hasParent"))
            }.getOrNull()
            selected(value)
        }
    }

    fun clear() {
        generation++
        if (injected) {
            web.evaluateJavascript(command("clear"), null)
            injected = false
        }
        selected(null)
    }
}
