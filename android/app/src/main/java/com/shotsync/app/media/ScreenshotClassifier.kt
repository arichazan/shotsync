package com.shotsync.app.media

/**
 * Pure screenshot classification logic (unit-testable, no Android deps).
 *
 * Screenshots are identified primarily by their MediaStore RELATIVE_PATH,
 * with a filename fallback. Manufacturer paths vary, so matching is
 * case-insensitive containment rather than exact equality.
 */
object ScreenshotClassifier {

    /** Standard screenshot directories across manufacturers. */
    val KNOWN_FOLDERS: Set<String> = setOf(
        "Pictures/Screenshots",      // Pixel, generic AOSP
        "DCIM/Screenshots",          // Samsung
        "Pictures/Screenshot",       // some Chinese OEMs
        "DCIM/ScreenShots",          // casing variants
        "Screenshots",
        "Pictures/screenshots",
        "DCIM/screenshots",
        "Storage/emulated/0/Pictures/Screenshots",
    )

    private val SCREENSHOT_NAME_MARKERS = listOf(
        "screenshot",
        "screen-shot",
        "screen_shot",
        "screenshots",
        "capture_",        // Huawei "capture_xxxx.png"? no - too broad; excluded
        "screenshot_",     // explicit
        "screenshot-",
        "screenshot_",     // Pixel/Samsung
    )

    /**
     * Returns true when the image should be treated as a screenshot.
     *
     * @param relativePath MediaStore RELATIVE_PATH (may be null on old APIs)
     * @param displayName  MediaStore DISPLAY_NAME
     * @param customFolders user-configured additional folders (exact match, case-insensitive)
     */
    fun isScreenshot(
        relativePath: String?,
        displayName: String,
        customFolders: Set<String>,
    ): Boolean {
        val path = relativePath?.trim() ?: ""
        if (path.isNotEmpty()) {
            val normalized = normalizePath(path)
            if (customFolders.any { normalizePath(it) == normalized }) return true
            if (KNOWN_FOLDERS.any { normalizePath(it) == normalized }) return true
            // Path may include a storage root prefix on some OEMs.
            if (normalized.contains("screenshot")) return true
        }
        // Filename fallback.
        return SCREENSHOT_NAME_MARKERS.any { marker ->
            displayName.lowercase().contains(marker)
        }
    }

    /** Normalizes a relative path for comparison. */
    fun normalizePath(path: String): String =
        path.trim().trim('/').trimEnd('/').lowercase()

    /** All folders to observe: defaults + user-configured ones. */
    fun observedFolders(custom: Set<String>): Set<String> =
        KNOWN_FOLDERS + custom
}
