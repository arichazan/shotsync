package com.shotsync.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)

    @Query("SELECT * FROM queue_items WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun findByMediaStoreId(mediaStoreId: String): QueueItem?

    @Query("SELECT * FROM queue_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): QueueItem?

    /** Oldest first, as required by the protocol (section 4.5). */
    @Query(
        "SELECT * FROM queue_items WHERE status IN (:statuses) " +
            "AND (:dueOnly = 0 OR nextAttemptAt IS NULL OR nextAttemptAt <= :now) " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun nextUploads(statuses: List<QueueStatus>, dueOnly: Boolean, now: Long, limit: Int): List<QueueItem>

    @Query("UPDATE queue_items SET status = :status, attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE id = :id")
    suspend fun bumpAttempt(id: Long, status: QueueStatus, now: Long)

    @Query("SELECT COUNT(*) FROM queue_items WHERE status IN (:statuses)")
    suspend fun countByStatus(statuses: List<QueueStatus>): Int

    @Query("SELECT COUNT(*) FROM queue_items")
    suspend fun totalCount(): Int

    @Query("SELECT * FROM queue_items ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_items WHERE status != 'COMPLETED'")
    suspend fun incomplete(): List<QueueItem>

    @Query("SELECT mediaStoreId FROM queue_items")
    suspend fun allMediaStoreIds(): List<String>

    @Query("SELECT mediaStoreId FROM queue_items WHERE status = 'COMPLETED' AND completedAt IS NOT NULL")
    suspend fun completedMediaStoreIds(): List<String>

    @Query("DELETE FROM queue_items WHERE status = 'COMPLETED' AND completedAt < :cutoff")
    suspend fun deleteCompletedOlderThan(cutoff: Long)

    @Query("DELETE FROM queue_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM queue_items WHERE status = 'FAILED'")
    suspend fun failed(): List<QueueItem>

    @Query("UPDATE queue_items SET status = 'READY', lastError = NULL, nextAttemptAt = NULL WHERE id = :id")
    suspend fun resetForRetry(id: Long)
}
