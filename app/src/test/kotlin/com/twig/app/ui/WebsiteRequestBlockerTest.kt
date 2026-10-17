package com.twig.app.ui

import org.junit.Assert.*
import org.junit.Test

class WebsiteRequestBlockerTest {
    @Test fun `domain rules include subdomains without matching unrelated hosts or URL text`() {
        val rules = requireNotNull(WebsiteRequestBlocker.parse(" ADS.Example.com\n\nads.example.com "))
        for (url in listOf("https://ads.example.com/banner.png", "http://a.ads.example.com:8080/pixel", "https://ADS.EXAMPLE.COM./track")) {
            assertTrue(url, rules.blocks(url))
        }
        for (url in listOf("https://badads.example.com", "https://ads.example.com.evil.test", "https://safe.test/?url=ads.example.com",
            "https://ads.example.com@safe.test/", "blob:https://ads.example.com/123", "javascript:ads.example.com", "not a URL")) {
            assertFalse(url, rules.blocks(url))
        }
        assertFalse(WebsiteRequestBlocker.EMPTY.blocks("https://ads.example.com/"))
    }

    @Test fun `URL rules match entire addresses and preserve case sensitive paths queries and regex characters`() {
        val rules = requireNotNull(WebsiteRequestBlocker.parse("https://EXAMPLE.com:443/Ads/(pixel).js?v=1\nhttp://example.com:8080/track*"))
        assertTrue(rules.blocks("https://example.com/Ads/(pixel).js?v=1#ignored"))
        assertFalse(rules.blocks("https://example.com/ads/(pixel).js?v=1"))
        assertFalse(rules.blocks("https://example.com/Ads/p.js?v=1"))
        assertFalse(rules.blocks("https://example.com/Ads/(pixel).js?v=12"))
        assertFalse(rules.blocks("http://example.com/Ads/(pixel).js?v=1"))
        assertTrue(rules.blocks("http://example.com:8080/track?a=1"))
        assertFalse(rules.blocks("http://example.com/track"))
    }

    @Test fun `wildcards work in domain and URL patterns without turning punctuation into regex`() {
        val rules = requireNotNull(WebsiteRequestBlocker.parse("*.track.test\nhttps://*.cdn.test:8443/ads/*?slot=*\nhttps://example.com/*pixel*.gif"))
        assertTrue(rules.blocks("https://a.b.track.test/pixel"))
        assertFalse(rules.blocks("https://track.test/pixel"))
        assertTrue(rules.blocks("https://img.cdn.test:8443/ads/banner.js?slot=123"))
        assertFalse(rules.blocks("https://img.cdn.test/ads/banner.js?slot=123"))
        assertFalse(rules.blocks("https://safe.test/path/img.cdn.test:8443/ads/banner.js?slot=123"))
        assertFalse(rules.blocks("https://img.cdn.test:8443/content/banner.js?slot=123"))
        assertTrue(rules.blocks("https://example.com/tracking/pixel-1.gif"))
        assertFalse(rules.blocks("https://example.com/tracking/pixel-1Xgif"))
        assertFalse(rules.blocks("https://example.com/tracking/pixel-1.gif?x=1"))
        assertTrue(requireNotNull(WebsiteRequestBlocker.parse("*")).blocks("https://anything.test/image"))
    }

    @Test fun `invalid input is rejected instead of silently accepting a partial blocklist`() {
        for (rule in listOf("https://", "https://user:pass@host.test/pixel", "file://host.test/ad", "javascript:alert(1)",
            "https://host.test:99999/ad", "https://host.test:0/ad", "https://host.test:*/ad", "host.test/path", "bad domain.test",
            "host..test", "-bad.test", "https://host.test/ad#fragment", "||host.test^", "https://*.test:bad/ad")) {
            assertNull(rule, WebsiteRequestBlocker.parse("valid.test\n$rule"))
        }
        assertNotNull(WebsiteRequestBlocker.parse("\r\n \n"))
        assertTrue(requireNotNull(WebsiteRequestBlocker.parse("例子.测试")).blocks("https://xn--fsqu00a.xn--0zwm56d/"))
    }
}
