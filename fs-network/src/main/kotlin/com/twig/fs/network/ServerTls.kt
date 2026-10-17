package com.twig.fs.network

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** UI/storage bridge. Decisions are scoped to the actual HTTPS host and port, including redirects. */
interface CertificateTrust {
    fun remembered(authority: String): String?
    fun confirm(authority: String, certificate: X509Certificate, previous: String?): Boolean
}

object ServerTls {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }

    fun configure(builder: OkHttpClient.Builder, trust: CertificateTrust?): OkHttpClient.Builder {
        if (trust == null) return builder
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val system = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val defaultHostname = OkHttpClient().hostnameVerifier
        // Defer server authentication to the hostname verifier below, where the actual
        // destination is available. No HTTP headers/body are sent before it succeeds.
        // Never install this manager globally or use its socket factory without that verifier.
        val deferred = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(deferred), null)
        return builder.sslSocketFactory(context.socketFactory, deferred)
            .hostnameVerifier { host, session ->
                val chain = session.peerCertificates.map { it as X509Certificate }.toTypedArray()
                val leaf = chain.first()
                val authority = if (host.contains(':')) "[$host]:${session.peerPort}" else "$host:${session.peerPort}"
                val fingerprint = fingerprint(leaf)
                val previous = trust.remembered(authority)
                if (previous != null && previous == fingerprint) true
                else {
                    val valid = try {
                        system.checkServerTrusted(chain, leaf.publicKey.algorithm)
                        defaultHostname.verify(host, session)
                    } catch (_: CertificateException) { false }
                    // Once explicitly trusted, a replacement always requires confirmation,
                    // even if the replacement now has a publicly trusted issuer.
                    if (previous == null && valid) true else trust.confirm(authority, leaf, previous)
                }
            }
    }
}
