package org.calyxos.backup.storage.db

import android.net.Uri
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

internal const val DB_MAX_OP = 750

@Database(entities = [StoredUri::class, CachedFile::class, CachedChunk::class], version = 1)
@TypeConverters(Converters::class)
internal abstract class Db : RoomDatabase() {
    abstract fun getUriStore(): UriStore
    abstract fun getFilesCache(): FilesCache
    abstract fun getChunksCache(): ChunksCache

    fun <T> applyInParts(list: Collection<T>, apply: (list: Collection<T>) -> Unit) =
        runInTransaction {
            list.chunked(DB_MAX_OP).forEach {
                apply(it)
            }
        }
}

internal class Converters {
    @TypeConverter
    fun uriToString(value: Uri?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun stringToUri(value: String?): Uri? {
        return value?.let { Uri.parse(it) }
    }

    @TypeConverter
    fun chunkIdsToString(value: List<String>?): String? {
        return value?.joinToString("#")
    }

    @TypeConverter
    fun stringToChunkIds(value: String?): List<String>? {
        return value?.split("#")
    }
}
