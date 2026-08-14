package com.shotsync.app.util

/**
 * Prepares filenames for the X-ShotSync-Filename header: strips newlines
 * and control characters (header-injection defense). Path separators are
 * left to the receiver's sanitizer, which strips directory components.
 */
object SafeFilename {

    fun forHeader(raw: String): String {
        val cleaned = raw.filter { c ->
            c.code >= 0x20 && c.code != 0x7f
        }.trim()
        return if (cleaned.isEmpty()) "screenshot" else cleaned
    }
}
