package com.shotsync.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecordingClassifierTest {

    private fun isRec(path: String?, name: String, custom: Set<String> = emptySet()) =
        ScreenRecordingClassifier.isRecording(path, name, custom)

    @Test
    fun `pixel recording folder matches`() {
        assertTrue(isRec("Movies/Screen records", "Screen_Recording_20260814_123456.mp4"))
    }

    @Test
    fun `samsung recording folder matches`() {
        assertTrue(isRec("Movies/Screen recordings", "Screen recording 2026-08-14 12.34.56.mp4"))
    }

    @Test
    fun `xiaomi recorder folder matches`() {
        assertTrue(isRec("Movies/ScreenRecorder", "Screenrecording_20260814.mp4"))
        assertTrue(isRec("DCIM/ScreenRecorder", "screenrecord-123.mp4"))
    }

    @Test
    fun `screencast folder matches`() {
        assertTrue(isRec("Movies/Screencasts", "screencast_1.webm"))
    }

    @Test
    fun `filename fallback catches recordings saved directly into Movies`() {
        assertTrue(isRec("Movies", "Screen_Recording_20260814_123456.mp4"))
        assertTrue(isRec("Movies", "Screen recording 2026-08-14.mp4"))
        assertTrue(isRec("Movies", "screenrecorder_20260814.mp4"))
    }

    @Test
    fun `pixel naming in movies folder matches`() {
        assertTrue(isRec("Movies", "screen-20250420-100710.mp4"))
        assertTrue(isRec("Movies", "screen-20260721-200420-1784657005345~2.mp4"))
    }

    @Test
    fun `regular videos are rejected`() {
        assertFalse(isRec("Movies", "VID_20260814_123456.mp4"))
        assertFalse(isRec("DCIM/Camera", "MVIMG_20260814.mp4"))
        assertFalse(isRec("Movies/WhatsApp", "WhatsApp-Video.mp4"))
        assertFalse(isRec("DCIM/Camera", "VID-20260814-WA0001.mp4"))
    }

    @Test
    fun `custom recording folders extend detection`() {
        assertTrue(isRec("Movies/MyCasts", "anything.mp4", custom = setOf("Movies/MyCasts")))
    }

    @Test
    fun `trailing slash and casing in relative path match`() {
        assertTrue(isRec("Movies/Screen records/", "20260814.mp4"))
        assertTrue(isRec("MOVIES/SCREENRECORDER/", "20260814.mp4"))
    }

    @Test
    fun `oem folder variants match by containment`() {
        assertTrue(isRec("Movies/Screen records 1", "clip.mp4"))
        assertTrue(isRec("DCIM/ScreenRecords/2026", "clip.mp4"))
        assertTrue(isRec("Movies/Screencasts/Old", "clip.webm"))
    }

    @Test
    fun `camera and messaging videos with screen-free names are rejected`() {
        assertFalse(isRec("DCIM/Camera", "PXL_20260814_094521321.mp4"))
        assertFalse(isRec("Movies/Telegram", "video_2026-08-14.mp4"))
        assertFalse(isRec(null, "VID_1.mp4"))
    }

    @Test
    fun `custom folder matching ignores case and slashes`() {
        assertTrue(isRec("movies/mycasts/", "a.mp4", custom = setOf("/Movies/MyCasts")))
    }

    @Test
    fun `observed folders include defaults and custom`() {
        val folders = ScreenRecordingClassifier.observedFolders(setOf("Movies/MyCasts"))
        assertTrue(folders.containsAll(ScreenRecordingClassifier.KNOWN_FOLDERS))
        assertTrue("Movies/MyCasts" in folders)
    }
}
