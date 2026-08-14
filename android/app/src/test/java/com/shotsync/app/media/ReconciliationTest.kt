package com.shotsync.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconciliationTest {

    private fun media(id: String, video: Boolean = false) = ScannedMedia(
        mediaStoreId = (if (video) "video:" else "") + id,
        contentUri = "content://media/external/items/$id",
        displayName = "Screen_$id.png",
        relativePath = "Pictures/Screenshots",
        mimeType = if (video) "video/mp4" else "image/png",
        size = 10,
        dateAddedSec = id.toLong(),
        dateTakenMs = null,
    )

    @Test
    fun `new media are enqueued`() {
        val scanned = listOf(media("1"), media("2"))
        val diff = Reconciliation.diff(scanned, setOf("9"))
        assertEquals(listOf("1", "2"), diff.map { it.mediaStoreId })
    }

    @Test
    fun `duplicate observer events are filtered out`() {
        val scanned = listOf(media("1"), media("2"), media("1"))
        val diff = Reconciliation.diff(scanned, setOf("1"))
        assertEquals(listOf("2"), diff.map { it.mediaStoreId })
    }

    @Test
    fun `already queued media are not re-enqueued`() {
        val scanned = listOf(media("1"), media("2"))
        val diff = Reconciliation.diff(scanned, setOf("1", "2"))
        assertEquals(emptyList<String>(), diff.map { it.mediaStoreId })
    }

    @Test
    fun `completed history also suppresses re-enqueue`() {
        val scanned = listOf(media("5"))
        val diff = Reconciliation.diff(scanned, setOf("5"))
        assertEquals(0, diff.size)
    }

    @Test
    fun `video and image ids stay distinct`() {
        val scanned = listOf(media("7", video = true), media("7", video = false))
        val diff = Reconciliation.diff(scanned, setOf("7"))
        assertEquals(listOf("video:7"), diff.map { it.mediaStoreId })
    }

    @Test
    fun `isVideo derives from mime type`() {
        assertEquals(true, media("1", video = true).isVideo)
        assertEquals(false, media("1").isVideo)
    }
}
