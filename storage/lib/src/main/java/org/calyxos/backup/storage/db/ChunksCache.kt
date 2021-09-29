package org.calyxos.backup.storage.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import org.calyxos.backup.storage.backup.Backup

@Entity
internal data class CachedChunk(
    @PrimaryKey val id: String,
    /**
     * How many snapshots are referencing this chunk.
     * Note that this is *not* about how many files across various snapshots are referencing it.
     */
    @ColumnInfo(name = "ref_count") val refCount: Long,
    val size: Long,
    val version: Byte = Backup.VERSION,
)

@Dao
internal interface ChunksCache {
    @Insert
    fun insert(chunk: CachedChunk)

    @Insert
    fun insert(chunks: Collection<CachedChunk>)

    @Query("SELECT * FROM CachedChunk WHERE id == (:id)")
    fun get(id: String): CachedChunk?

    @Query("SELECT COUNT(id) FROM CachedChunk WHERE id IN (:ids)")
    fun getNumberOfCachedChunks(ids: Collection<String>): Int

    @Query("SELECT * FROM CachedChunk WHERE ref_count <= 0")
    fun getUnreferencedChunks(): List<CachedChunk>

    @Query("UPDATE CachedChunk SET ref_count = ref_count + 1 WHERE id IN (:ids)")
    fun incrementRefCount(ids: Collection<String>)

    @Query("UPDATE CachedChunk SET ref_count = ref_count - 1 WHERE id IN (:ids)")
    fun decrementRefCount(ids: Collection<String>)

    @Delete
    fun deleteChunks(chunks: List<CachedChunk>)

    @Query("DELETE FROM CachedChunk")
    fun clear()

    fun areAllAvailableChunksCached(db: Db, availableChunks: Collection<String>): Boolean {
        return db.runInTransaction<Boolean> {
            var allCached = true
            availableChunks.chunked(DB_MAX_OP).forEach { availableChunkIds ->
                val num = getNumberOfCachedChunks(availableChunkIds)
                allCached = allCached && availableChunkIds.size == num
                if (!allCached) return@runInTransaction false
            }
            return@runInTransaction allCached
        }
    }

    fun clearAndRepopulate(db: Db, chunks: Collection<CachedChunk>) = db.runInTransaction {
        clear()
        chunks.chunked(DB_MAX_OP).forEach {
            insert(it)
        }
    }

}
