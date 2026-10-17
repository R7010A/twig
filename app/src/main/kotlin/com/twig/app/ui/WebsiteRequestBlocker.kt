package com.twig.app.ui

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Immutable rules shared with WebView's resource-request thread. No proxy or remote lists. */
internal class WebsiteRequestBlocker private constructor(private val rules: List<Rule>) {
    private data class Rule(val pattern: String, val domain: Boolean, val wildcard: Boolean)

    fun blocks(url: String): Boolean {
        if (rules.isEmpty()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val address = address(uri) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return rules.any { rule ->
            if (rule.domain) {
                if (rule.wildcard) glob(rule.pattern, host)
                else host == rule.pattern || host.endsWith("." + rule.pattern)
            } else {
                // A wildcard in the host must not consume a path containing a different host name.
                val split = rule.pattern.indexOf('/', rule.pattern.indexOf("://") + 3)
                val targetSplit = address.indexOf('/', address.indexOf("://") + 3)
                glob(rule.pattern.substring(0, split), address.substring(0, targetSplit)) &&
                    glob(rule.pattern.substring(split), address.substring(targetSplit))
            }
        }
    }

    companion object {
        val EMPTY = WebsiteRequestBlocker(emptyList())

        /** A bare domain includes subdomains. URL patterns match the entire URL, excluding its fragment. */
        fun parse(text: String): WebsiteRequestBlocker? {
            val rules = mutableListOf<Rule>()
            for (line in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                if (line.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) return null
                if ("://" in line) {
                    val uri = runCatching { URI(line) }.getOrNull() ?: return null
                    if (uri.rawFragment != null) return null
                    val pattern = address(uri) ?: return null
                    if (!validHost(runCatching { URI(line.replace("*", "wildcard")).host }.getOrNull() ?: return null)) return null
                    rules.add(Rule(pattern, false, '*' in line))
                } else {
                    val host = runCatching { IDN.toASCII(line, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
                        ?: runCatching {
                            // IDN rejects '*'; validate its literal surroundings using an ordinary label.
                            IDN.toASCII(line.replace("*", "wildcard"), IDN.USE_STD3_ASCII_RULES)
                            line.takeIf { it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in ".-*" } }
                        }.getOrNull() ?: return null
                    if (!validHost(host.replace("*", "wildcard"))) return null
                    rules.add(Rule(host.lowercase(Locale.ROOT).trimEnd('.'), true, '*' in host))
                }
            }
            return WebsiteRequestBlocker(rules.toList())
        }

        private fun validHost(host: String): Boolean = runCatching {
            host.isNotEmpty() && IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).isNotEmpty()
        }.getOrDefault(false)

        private fun address(uri: URI): String? {
            val literal = runCatching { URI(uri.toString().replace("*", "wildcard")) }.getOrNull() ?: return null
            val scheme = literal.scheme?.lowercase(Locale.ROOT)
            if (scheme !in listOf("https", "http") || literal.rawUserInfo != null ||
                literal.port !in -1..65535 || literal.port == 0 || literal.host.isNullOrEmpty()) return null
            val host = uri.host ?: uri.rawAuthority?.substringBefore(':') ?: return null
            val port = literal.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
            return "$scheme://${host.lowercase(Locale.ROOT).trimEnd('.')}${port?.let { ":$it" }.orEmpty()}" +
                uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        }

        /** Treat only '*' specially; regex metacharacters remain literal and cannot cause regex backtracking. */
        private fun glob(pattern: String, value: String): Boolean {
            var p = 0
            var v = 0
            var star = -1
            var retry = 0
            while (v < value.length) {
                when {
                    p < pattern.length && pattern[p] == '*' -> { star = p++; retry = v }
                    p < pattern.length && pattern[p] == value[v] -> { p++; v++ }
                    star >= 0 -> { p = star + 1; v = ++retry }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
