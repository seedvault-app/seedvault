package org.calyxos.backup.storage.db

import android.net.Uri
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity
internal data class StoredUri(
    @PrimaryKey val uri: Uri,
)

@Dao
internal interface UriStore {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addStoredUri(uri: StoredUri)

    @Delete
    fun removeStoredUri(uri: StoredUri)

    @Query("SELECT * FROM StoredUri")
    fun getStoredUris(): List<StoredUri>

}
