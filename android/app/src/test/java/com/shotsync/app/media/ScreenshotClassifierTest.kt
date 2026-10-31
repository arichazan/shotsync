package com.shotsync.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotClassifierTest {

    private fun isShot(path: String?, name: String, custom: Set<String> = emptySet()) =
        ScreenshotClassifier.isScreenshot(path, name, custom)

    @Test
    fun `pixel screenshot path matches`() {
        assertTrue(isShot("Pictures/Screenshots", "Screenshot_20260814_094521.png"))
    }

    @Test
    fun `samsung dcim path matches`() {
        assertTrue(isShot("DCIM/Screenshots", "Screenshot_20260814-094521.png"))
    }

    @Test
    fun `lowercase path variant matches`() {
        assertTrue(isShot("Pictures/screenshots", "screenshot.png"))
    }

    @Test
    fun `manufacturer variant with different casing matches`() {
        assertTrue(isShot("DCIM/ScreenShots", "Screenshot (1).png"))
        assertTrue(isShot("Pictures/Screenshot", "Screenshot.jpg"))
    }

    @Test
    fun `xiami-style path matches`() {
        assertTrue(isShot("DCIM/Screenshots", "Screenshot_2026-08-14-09-45-21_com.miui.home.png"))
    }

    @Test
    fun `unknown screenshot-like path still matches via containment`() {
        assertTrue(isShot("Pictures/CoolScreenshots", "20260814.png"))
    }

    @Test
    fun `filename fallback catches misplaced screenshots`() {
        assertTrue(isShot("Pictures/WeChat", "Screenshot_20260814.png"))
        assertTrue(isShot("Download", "screenshot-1234.png"))
        assertTrue(isShot(null, "Screen_Shot_2026.png"))
    }

    @Test
    fun `regular photos are rejected`() {
        assertFalse(isShot("DCIM/Camera", "IMG_20260814_094521.jpg"))
        assertFalse(isShot("Pictures/WeChat", "mmexport1692012345678.jpg"))
        assertFalse(isShot("DCIM/Camera", "PXL_20260814_094521321.jpg"))
    }

    @Test
    fun `custom folders extend detection`() {
        assertTrue(isShot("Pictures/MyShots", "IMG_0001.png", custom = setOf("Pictures/MyShots")))
    }

    @Test
    fun `normalizePath trims and lowercases`() {
        assertEquals("pictures/screenshots", ScreenshotClassifier.normalizePath("/Pictures/Screenshots/"))
        assertEquals("dcim/camera", ScreenshotClassifier.normalizePath("  DCIM/Camera "))
    }

    @Test
    fun `mediastore relative paths with trailing slash match`() {
        assertTrue(isShot("Pictures/Screenshots/", "20260814_094521.png"))
        assertTrue(isShot("DCIM/Screenshots/", "20260814_094521.jpg"))
    }

    @Test
    fun `custom folder matching ignores case and slashes`() {
        assertTrue(isShot("pictures/myshots/", "IMG_0001.png", custom = setOf("/Pictures/MyShots")))
        assertFalse(isShot("Pictures/MyShots/Sub", "IMG_0001.png", custom = setOf("Pictures/MyShots")))
    }

    @Test
    fun `blank path falls back to filename`() {
        assertTrue(isShot("   ", "Screenshot_1.png"))
        assertFalse(isShot("", "IMG_1.png"))
        assertFalse(isShot(null, ""))
    }

    @Test
    fun `filename markers are case insensitive`() {
        assertTrue(isShot("Download", "SCREENSHOT_2026.PNG"))
        assertTrue(isShot("Download", "Screen-Shot 2026.png"))
    }

    @Test
    fun `observed folders include defaults and custom`() {
        val folders = ScreenshotClassifier.observedFolders(setOf("Pictures/MyShots"))
        assertTrue(folders.containsAll(ScreenshotClassifier.KNOWN_FOLDERS))
        assertTrue("Pictures/MyShots" in folders)
    }
}
