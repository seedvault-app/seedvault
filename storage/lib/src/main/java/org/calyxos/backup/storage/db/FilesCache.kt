/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.db

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity
internal data class CachedFile(
    @PrimaryKey val uri: Uri,
    val size: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long?,
    @ColumnInfo(name = "generation_modified") val generationModified: Long? = null,
    val chunks: List<String>,
    /**
     * The index in the single Zip Chunk.
     * If this is not null, [chunks] must be of size 1.
     */
    @ColumnInfo(name = "zip_index") val zipIndex: Int? = null,
    // TODO also purge files from the cache from time to time
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
)

@Dao
internal interface FilesCache {
    @Insert
    fun insert(file: CachedFile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(file: CachedFile)

    @Update
    fun update(file: CachedFile)

    @Query("DELETE FROM CachedFile")
    fun clear()

    @Query("UPDATE CachedFile SET last_seen = :now WHERE uri IN (:uris)")
    fun updateLastSeen(uris: Collection<Uri>, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM CachedFile WHERE uri == (:uri)")
    fun getByUri(uri: Uri): CachedFile?
}
