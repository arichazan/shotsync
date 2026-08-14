package com.shotsync.app.sync

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Trust manager that pins the Mac's self-signed certificate by SHA-256
 * fingerprint (exchanged out-of-band during QR pairing). Chain validation is
 * replaced by the pin comparison — the pinned cert is the only one accepted.
 * TLS certificate validation is never disabled globally: it is narrowed to
 * exactly one expected certificate.
 */
class PinningTrustManager(private val pinnedFingerprint: String?) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw java.security.cert.CertificateException("client certificates not used")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val pin = pinnedFingerprint
            ?: throw java.security.cert.CertificateException("no pinned certificate configured")
        val leaf = chain.firstOrNull()
            ?: throw java.security.cert.CertificateException("empty certificate chain")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val actual = "sha256-" + Base64.getEncoder().encodeToString(digest)
        if (!constantTimeEquals(actual, pin)) {
            throw java.security.cert.CertificateException("certificate does not match pinned fingerprint")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    companion object {
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }
    }
}

/**
 * Hostname verifier used together with the pin. The pin already proves the
 * certificate identity, so hostname verification becomes a defense-in-depth
 * allowlist of the endpoints we are configured to talk to.
 */
class AllowlistedHostnameVerifier(
    private val allowedHosts: () -> Set<String>,
) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val hosts = allowedHosts()
        if (hosts.any { it.equals(hostname, ignoreCase = true) }) return true
        val bare = hostname.trimEnd('.')
        if (hosts.any { it.trimEnd('.').equals(bare, ignoreCase = true) }) return true
        // IP literals (local-network IPs discovered via mDNS) and Tailscale
        // magic-DNS names are acceptable when the certificate pin matches.
        if (isIpLiteral(bare)) return true
        if (bare.endsWith(".ts.net")) return true
        return false
    }

    private fun isIpLiteral(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        if (host.contains(':')) return true
        return false
    }
}
