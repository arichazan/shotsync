package com.shotsync.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.shotsync.app.util.AppLogger
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A media item (screenshot or screen recording) discovered by a scan. */
data class ScannedMedia(
    val mediaStoreId: String, // "video:"-prefixed for recordings
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long,
    val dateAddedSec: Long,
    val dateTakenMs: Long?,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/** Pure diff logic for the reconciliation scan (unit-testable). */
object Reconciliation {
    /**
     * Returns scanned media not yet tracked in the queue. Duplicate
     * MediaStore notifications are handled by the unique mediaStoreId index;
     * identical content with different filenames is deduplicated by the
     * receiver's content-hash check at upload time.
     */
    fun diff(scanned: List<ScannedMedia>, knownMediaStoreIds: Set<String>): List<ScannedMedia> =
        scanned.filter { it.mediaStoreId !in knownMediaStoreIds }
}

/**
 * MediaStore access: scans for screenshots (Images) and screen recordings
 * (Video) and streams their content for hashing without loading whole files
 * into memory.
 *
 * SQL selections deliberately avoid functions like LOWER(): several OEM
 * MediaStore providers reject them with "Invalid token". Classification
 * happens in Kotlin via [ScreenshotClassifier] / [ScreenRecordingClassifier].
 */
class MediaScanner(private val context: Context) {

    private val resolver = context.contentResolver

    private val mediaProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.DATE_TAKEN,
    )

    /** Lists screenshot folders present on the device (for setup UI). */
    suspend fun discoverScreenshotFolders(maxFolders: Int = 40): List<String> =
        discoverFolders(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "screenshot", maxFolders)

    /** Lists screen-recording folders present on the device (for setup UI). */
    suspend fun discoverRecordingFolders(maxFolders: Int = 40): List<String> =
        discoverFolders(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "screen", maxFolders)

    private suspend fun discoverFolders(uri: Uri, marker: String, maxFolders: Int): List<String> =
        withContext(Dispatchers.IO) {
            val folders = linkedSetOf<String>()
            val seen = mutableSetOf<String>()
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext() && folders.size < maxFolders) {
                    val path = cursor.getString(col) ?: continue
                    if (path.isBlank()) continue
                    val normalized = ScreenshotClassifier.normalizePath(path)
                    if (normalized.contains(marker) && seen.add(normalized)) {
                        folders += path
                    }
                }
            }
            folders.toList()
        }

    /**
     * Scans images added after [sinceSec], newest first, returning only
     * screenshots. Pending (still being written) items are skipped.
     */
    suspend fun scanImagesSince(
        sinceSec: Long,
        customFolders: Set<String>,
        maxResults: Int = 1000,
    ): List<ScannedMedia> =
        scanTable(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            sinceSec = sinceSec,
            maxResults = maxResults,
        ) { path, name ->
            ScreenshotClassifier.isScreenshot(path, name, customFolders)
        }

    /**
     * Scans videos added after [sinceSec], newest first, returning only
     * screen recordings.
     */
    suspend fun scanVideosSince(
        sinceSec: Long,
        customFolders: Set<String>,
        maxResults: Int = 200,
    ): List<ScannedMedia> =
        scanTable(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            sinceSec = sinceSec,
            maxResults = maxResults,
            prefix = "video:",
        ) { path, name ->
            ScreenRecordingClassifier.isRecording(path, name, customFolders)
        }

    private suspend fun scanTable(
        uri: Uri,
        sinceSec: Long,
        maxResults: Int,
        prefix: String = "",
        classify: (relativePath: String, displayName: String) -> Boolean,
    ): List<ScannedMedia> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ScannedMedia>()
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        val args = arrayOf(sinceSec.toString())
        resolver.query(
            uri,
            mediaProjection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val pendingCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            AppLogger.d("MediaScanner", "scan $uri: ${cursor.count} rows, since=$sinceSec")
            while (cursor.moveToNext() && out.size < maxResults) {
                val pending = if (pendingCol >= 0) cursor.getInt(pendingCol) else 0
                if (pending == 1) continue // file still being written
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val path = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                if (!classify(path, name)) continue
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L
                val added = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                val taken = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else null
                val contentUri = ContentUris.withAppendedId(uri, id)
                out += ScannedMedia(
                    mediaStoreId = prefix + id.toString(),
                    contentUri = contentUri.toString(),
                    displayName = name,
                    relativePath = path,
                    mimeType = mime,
                    size = size,
                    dateAddedSec = added,
                    dateTakenMs = taken,
                )
            }
        }
        out
    }

    /**
     * Waits until the media item is no longer pending, up to [timeoutMs].
     * Returns true once stable. For recordings this also means the screen
     * recorder has finished writing.
     */
    suspend fun waitForCompletion(mediaStoreId: String, timeoutMs: Long = 60_000): Boolean =
        withContext(Dispatchers.IO) {
            val isVideo = mediaStoreId.startsWith("video:")
            val rawId = mediaStoreId.removePrefix("video:").toLongOrNull() ?: return@withContext false
            val uri = ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                rawId
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pending = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && c.columnCount > 0 && !c.isNull(0)) c.getInt(0) else 0
                } ?: 0
                if (pending == 0) return@withContext true
                delay(500)
            }
            false
        }

    /** Streams the media item through SHA-256; never buffers the whole file. */
    suspend fun computeSha256(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            AppLogger.w("MediaScanner", "hash failed for $uri: ${e.message}")
            null
        }
    }

    /** Formats DATE_TAKEN (ms epoch) into ISO-8601 for the Captured-At header. */
    fun formatIso8601(epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0) return null
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        return sdf.format(java.util.Date(epochMs))
    }

    fun buildContentUri(mediaStoreId: String): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId.toLong())

    companion object {
        val SUPPORTS_IS_PENDING = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
