package com.shotsync.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeFilenameTest {

    @Test
    fun `plain names pass through`() {
        assertEquals("Screenshot_2026-08-14_09-45-21.png", SafeFilename.forHeader("Screenshot_2026-08-14_09-45-21.png"))
    }

    @Test
    fun `newlines and control characters are stripped`() {
        assertEquals("evil.png", SafeFilename.forHeader("evil\r\n.png"))
        assertEquals("ab.png", SafeFilename.forHeader("a\tb.png"))
    }

    @Test
    fun `path separators are left for receiver sanitization`() {
        assertEquals("../../etc/passwd", SafeFilename.forHeader("../../etc/passwd"))
    }

    @Test
    fun `empty names fall back safely`() {
        assertEquals("screenshot", SafeFilename.forHeader(""))
        assertEquals("screenshot", SafeFilename.forHeader("   "))
    }
}
