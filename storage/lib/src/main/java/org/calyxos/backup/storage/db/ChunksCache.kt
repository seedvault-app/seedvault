/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.db

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.IGNORE
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import org.calyxos.backup.storage.backup.Backup

@Entity
internal data class CachedChunk(
    @PrimaryKey val id: String,
    /**
     * How many snapshots are referencing this chunk.
     * Note that this is *not* about how many files across various snapshots are referencing it.
     */
    @ColumnInfo(name = "ref_count") val refCount: Long,
    /**
     * The ciphertext size of the chunk on disk, including the version byte.
     * Not the plaintext size.
     */
    val size: Long,
    val version: Byte = Backup.VERSION,
    @ColumnInfo(defaultValue = "0")
    val corrupted: Boolean = false,
)

@Dao
internal interface ChunksCache {
    @VisibleForTesting
    @Insert(onConflict = IGNORE)
    fun insertInternal(chunk: CachedChunk): Long

    /**
     * Should only get used by [clearAndRepopulate].
     */
    @Insert
    @VisibleForTesting
    fun insert(chunks: Collection<CachedChunk>)

    /**
     * Returns the [CachedChunk] with the given [id], if it exists in the database
     * and is not marked as [CachedChunk.corrupted].
     */
    @Query("SELECT * FROM CachedChunk WHERE id == (:id) AND corrupted = 0")
    fun get(id: String): CachedChunk?

    /**
     * Returns the [CachedChunk] with the given [id], if it exists in the database
     * no mater if it is marked as [CachedChunk.corrupted] or not.
     */
    @Query("SELECT * FROM CachedChunk WHERE id == (:id)")
    fun getEvenIfCorrupted(id: String): CachedChunk?

    @VisibleForTesting
    @Query("SELECT COUNT(id) FROM CachedChunk WHERE id IN (:ids)")
    fun getNumberOfCachedChunks(ids: Collection<String>): Int

    @Query("SELECT SUM(size) FROM CachedChunk WHERE ref_count > 0")
    fun getSizeOfCachedChunks(): Long

    @Query("SELECT * FROM CachedChunk WHERE ref_count <= 0")
    fun getUnreferencedChunks(): List<CachedChunk>

    @Query("UPDATE CachedChunk SET ref_count = ref_count + 1 WHERE id IN (:ids)")
    fun incrementRefCount(ids: Collection<String>)

    @Query("UPDATE CachedChunk SET ref_count = ref_count - 1 WHERE id IN (:ids)")
    fun decrementRefCount(ids: Collection<String>)

    @Query("UPDATE CachedChunk SET corrupted = 1 WHERE id == :id")
    fun markCorrupted(id: String)

    @VisibleForTesting
    @Query("UPDATE CachedChunk SET corrupted = 0 WHERE id == :id")
    fun unmarkCorrupted(id: String)

    @Query("SELECT COUNT(*) FROM CachedChunk WHERE id IN (:ids) AND corrupted = 1")
    fun hasCorruptedChunks(ids: Collection<String>): Boolean

    @Delete
    fun deleteChunks(chunks: List<CachedChunk>)

    @Query("DELETE FROM CachedChunk")
    fun clear()

    @Transaction
    fun insert(chunk: CachedChunk) {
        val result = insertInternal(chunk)
        if (result < 0) {
            Log.d("ChunksCache", "Chunk ${chunk.id} already existed in DB.")
            val existingChunk = getEvenIfCorrupted(chunk.id) ?: error("No chunk ${chunk.id} in DB")
            check(existingChunk.corrupted) { "Chunk ${chunk.id} wasn't marked as corrupted." }
            if (existingChunk.size != chunk.size) {
                Log.w("ChunksCache", "New chunk size ${chunk.size}, expected ${existingChunk.size}")
            }
            unmarkCorrupted(chunk.id)
        }
    }

    @Transaction
    fun areAllAvailableChunksCached(availableChunks: Collection<String>): Boolean {
        availableChunks.chunked(DB_MAX_OP).forEach { availableChunkIds ->
            val num = getNumberOfCachedChunks(availableChunkIds)
            if (availableChunkIds.size != num) return false
        }
        return true
    }

    @Transaction
    fun clearAndRepopulate(chunks: Collection<CachedChunk>) {
        clear()
        chunks.chunked(DB_MAX_OP).forEach {
            insert(it)
        }
    }
}
