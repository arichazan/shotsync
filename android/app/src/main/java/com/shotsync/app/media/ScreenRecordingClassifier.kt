package com.shotsync.app.media

/**
 * Pure screen-recording classification for MediaStore.Video entries
 * (unit-testable, no Android deps). Recordings are matched by their
 * relative path (manufacturer-specific folders) with a filename fallback
 * for OEMs that save directly into Movies/.
 */
object ScreenRecordingClassifier {

    /** Recording directories across manufacturers. */
    val KNOWN_FOLDERS: Set<String> = setOf(
        "Movies/Screen records",       // Pixel
        "Movies/ScreenRecords",
        "Movies/Screen recordings",    // Samsung
        "Movies/ScreenRecordings",
        "Movies/ScreenRecorder",       // Xiaomi/Oppo
        "Movies/Screenrecorder",
        "Movies/Recordings",
        "Movies/Screencasts",
        "DCIM/ScreenRecorder",
        "Movies/screen records",
        "Movies/screenrecords",
    )

    private val NAME_MARKERS = listOf(
        "screen recording",
        "screen_recording",
        "screen-recording",
        "screenrecord",
        "screen_record",
        "screen-record",
        "screen record",
        "screencast",
        "screen capture",
        "screencapture",
        "screen-", // Pixel: screen-20250420-100710.mp4
        "screen_",
    )

    /**
     * Returns true when the video should be treated as a screen recording.
     */
    fun isRecording(
        relativePath: String?,
        displayName: String,
        customFolders: Set<String>,
    ): Boolean {
        val path = relativePath?.trim() ?: ""
        if (path.isNotEmpty()) {
            val normalized = ScreenshotClassifier.normalizePath(path)
            if (customFolders.any { ScreenshotClassifier.normalizePath(it) == normalized }) return true
            if (KNOWN_FOLDERS.any { ScreenshotClassifier.normalizePath(it) == normalized }) return true
            // Paths like "Movies/Screen records 1" or OEM variants.
            if (normalized.contains("screenrec") || normalized.contains("screen rec") ||
                normalized.contains("screencast")
            ) return true
        }
        val name = displayName.lowercase()
        if (NAME_MARKERS.any { name.contains(it) }) return true
        // Pixel saves directly into Movies/ as screen-<timestamp>.mp4.
        return name.startsWith("screen")
    }

    /** All recording folders to observe: defaults + user-configured. */
    fun observedFolders(custom: Set<String>): Set<String> = KNOWN_FOLDERS + custom
}
