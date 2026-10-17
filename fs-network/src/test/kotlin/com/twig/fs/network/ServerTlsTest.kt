package com.twig.fs.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

class ServerTlsTest {
    private class Trust : CertificateTrust {
        val pins = mutableMapOf<String, String>()
        val prompts = mutableListOf<Pair<String, String?>>()
        var accept = true
        override fun remembered(authority: String) = pins[authority]
        override fun confirm(authority: String, certificate: X509Certificate, previous: String?): Boolean {
            prompts += authority to previous
            if (accept) pins[authority] = ServerTls.fingerprint(certificate)
            return accept
        }
    }

    private fun server(): MockWebServer {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tls/server.p12")!!.use { store.load(it, "test-password".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "test-password".toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        return MockWebServer().apply {
            useHttps(context.socketFactory, false)
            start()
        }
    }

    private fun fetch(server: MockWebServer, trust: CertificateTrust?): String {
        val client = ServerTls.configure(OkHttpClient.Builder(), trust)
            .readTimeout(2, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url(server.url("/")).header("Authorization", "secret").build())
                .execute().use { it.body!!.string() }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun approvalIsRememberedAcrossClients() {
        server().use { server ->
            val trust = Trust()
            repeat(2) {
                server.enqueue(MockResponse().setBody("ok"))
                assertEquals("ok", fetch(server, trust))
            }
            assertEquals(listOf("localhost:${server.port}" to null), trust.prompts)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun refusalSendsNoCredentialsOrRequestAndDoesNotRemember() {
        server().use { server ->
            val trust = Trust().apply { accept = false }
            try {
                fetch(server, trust)
                fail("Unapproved certificate must fail")
            } catch (_: SSLPeerUnverifiedException) { }
            assertEquals(0, server.requestCount)
            assertTrue(trust.pins.isEmpty())
        }
    }

    @Test fun replacementRequiresApprovalAndPreservesOldPinOnRefusal() {
        server().use { server ->
            val authority = "localhost:${server.port}"
            val trust = Trust().apply { pins[authority] = "old-fingerprint"; accept = false }
            try {
                fetch(server, trust)
                fail("Changed certificate must fail")
            } catch (_: SSLPeerUnverifiedException) { }
            assertEquals("old-fingerprint", trust.pins[authority])
            assertEquals(listOf(authority to "old-fingerprint"), trust.prompts)
            trust.accept = true
            server.enqueue(MockResponse().setBody("ok"))
            assertEquals("ok", fetch(server, trust))
            assertNotEquals("old-fingerprint", trust.pins[authority])
        }
    }

    @Test fun trustDoesNotExtendToAnotherPort() {
        server().use { first ->
            server().use { second ->
                val trust = Trust()
                first.enqueue(MockResponse().setBody("first"))
                fetch(first, trust)
                trust.accept = false
                try {
                    fetch(second, trust)
                    fail("A different endpoint needs its own decision")
                } catch (_: SSLPeerUnverifiedException) { }
                assertEquals(2, trust.prompts.size)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test fun redirectsRequireTrustForActualDestination() {
        server().use { first ->
            server().use { second ->
                val trust = Trust()
                first.enqueue(MockResponse().setBody("ok"))
                fetch(first, trust)
                trust.accept = false
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/")))
                try {
                    fetch(first, trust)
                    fail("Redirect must not inherit source trust")
                } catch (_: SSLPeerUnverifiedException) { }
                assertEquals("localhost:${second.port}", trust.prompts.last().first)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test fun absentBridgeKeepsSystemValidation() {
        server().use { server ->
            try {
                fetch(server, null)
                fail("Default client must reject a self-signed certificate")
            } catch (_: javax.net.ssl.SSLHandshakeException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
