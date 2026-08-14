package com.shotsync.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

    private val valid = """
        {"version":1,"device_id":"abc-123","device_name":"ShotSync Receiver",
         "local_host":"shotsync-a1b2c3d4e5f6.local","port":47831,
         "certificate_fingerprint":"sha256-AAAA","pairing_secret":"s3cr3t-with-more-than-32-chars-of-entropy-ok"}
    """.trimIndent()

    @Test
    fun `valid payload parses`() {
        val p = QrPayload.parse(valid)!!
        assertEquals(1, p.version)
        assertEquals("abc-123", p.deviceId)
        assertEquals("shotsync-a1b2c3d4e5f6.local", p.localHost)
        assertEquals(47831, p.port)
        assertEquals("sha256-AAAA", p.certificateFingerprint)
        assertEquals("s3cr3t-with-more-than-32-chars-of-entropy-ok", p.pairingSecret)
    }

    @Test
    fun `wrong version rejected`() {
        assertNull(QrPayload.parse(valid.replace("\"version\":1", "\"version\":2")))
    }

    @Test
    fun `missing fingerprint rejected`() {
        assertNull(QrPayload.parse(valid.replace("\"certificate_fingerprint\":\"sha256-AAAA\"", "")))
    }

    @Test
    fun `short secret rejected`() {
        assertNull(QrPayload.parse(valid.replace("s3cr3t-with-more-than-32-chars-of-entropy-ok", "tiny")))
    }

    @Test
    fun `garbage rejected`() {
        assertNull(QrPayload.parse("not json"))
        assertNull(QrPayload.parse("{}"))
    }
}
