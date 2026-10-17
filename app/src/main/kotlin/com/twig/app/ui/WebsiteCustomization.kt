package com.twig.app.ui

import com.twig.app.Website
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Navigation headers and page customizations stay scoped to the bookmark's origin. */
internal object WebsiteCustomization {
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val managedHeaders = setOf("host", "content-length", "transfer-encoding", "connection",
        "upgrade", "proxy-connection", "proxy-authorization", "trailer", "te", "user-agent")

    fun parseHeaders(text: String): LinkedHashMap<String, String>? {
        if (text.startsWith("enc1:")) return null // A locked store must not send ciphertext as a header.
        val normalized = text.replace("\r\n", "\n")
        if ('\r' in normalized) return null
        val result = linkedMapOf<String, String>()
        val names = hashSetOf<String>()
        for (line in normalized.lineSequence()) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim(' ', '\t')
            val lower = name.lowercase(Locale.ROOT)
            if (!headerName.matches(name) || lower in managedHeaders || !names.add(lower) ||
                value.any { it != '\t' && (it.code < 32 || it.code == 127) }) return null
            result[name] = value
        }
        return result
    }

    fun validUserAgent(value: String) = value.none { it.code < 32 || it.code == 127 }

    fun sameOrigin(first: String, second: String?): Boolean = runCatching {
        if (second == null) return false
        val a = URI(first)
        val b = URI(second)
        fun scheme(uri: URI) = uri.scheme?.lowercase(Locale.ROOT)
        fun port(uri: URI) = if (uri.port != -1) uri.port else if (scheme(uri) == "https") 443 else 80
        scheme(a) in setOf("http", "https") && scheme(a) == scheme(b) &&
            a.host != null && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }.getOrDefault(false)

    fun headersFor(site: Website, url: String): Map<String, String> =
        if (sameOrigin(site.url, url)) parseHeaders(site.headers).orEmpty() else emptyMap()

    private fun quote(value: String) = JSONObject.quote(value)
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun guard(site: Website) = "if(location.origin!==new URL(${quote(site.url)}).origin)return;"

    fun requestKey(site: Website): String = digest(org.json.JSONArray()
        .put(site.desktopSite).put(site.userAgent).put(site.headers).put(site.script).toString())

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun styleScript(site: Website): String = """
        (() => {
            ${guard(site)}
            const key = ${quote("__twig_user_style_" + site.id)};
            let style = document.getElementById(key);
            const css = ${quote(site.style)};
            if (!css) { if (style) style.remove(); return; }
            if (!style || style.tagName !== 'STYLE') {
                if (style) style.remove();
                style = document.createElement('style');
                style.id = key;
                const source = document.querySelector('style[nonce],script[nonce]');
                if (source && source.nonce) style.nonce = source.nonce;
                (document.head || document.documentElement).appendChild(style);
            }
            style.textContent = css;
        })();
    """.trimIndent()

    /** Executed after page load, once per document, with ordinary page privileges. */
    fun pageScript(site: Website): String? {
        if (site.script.isBlank()) return null
        val revision = digest(site.script)
        return """
            (() => {
                ${guard(site)}
                const key = ${quote("__twig_user_script_" + site.id)};
                if (window[key] === ${quote(revision)}) return;
                window[key] = ${quote(revision)};
                try {
                    (function() {
                        ${site.script}
                    }).call(window);
                } catch (error) { console.error('Twig custom script:', error); }
            })();
        """.trimIndent()
    }
}
