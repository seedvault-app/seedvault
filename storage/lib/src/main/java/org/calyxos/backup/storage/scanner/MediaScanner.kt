package org.calyxos.backup.storage.scanner

import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns.IS_DOWNLOAD
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.content.MediaFile
import java.io.File

public class MediaScanner(context: Context) {

    private companion object {
        private val PROJECTION_29 = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.VOLUME_NAME,
        )

        @RequiresApi(30)
        private val PROJECTION_30 = arrayOf(
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        private const val PROJECTION_ID = 0
        private const val PROJECTION_PATH = 1
        private const val PROJECTION_NAME = 2
        private const val PROJECTION_DATE_MODIFIED = 3
        private const val PROJECTION_SIZE = 4
        private const val PROJECTION_OWNER_PACKAGE_NAME = 5
        private const val PROJECTION_VOLUME_NAME = 6
        private const val PROJECTION_IS_FAVORITE = 7
        private const val PROJECTION_GENERATION_MODIFIED = 8

    }

    private val contentResolver = context.contentResolver

    public fun scanUri(uri: Uri, maxSize: Long = Long.MAX_VALUE): List<BackupFile> {
        return scanMediaUri(uri, "${MediaStore.MediaColumns.SIZE}<$maxSize")
    }

    internal fun scanMediaUri(uri: Uri, extraQuery: String? = null): List<MediaFile> {
        val extras = Bundle().apply {
            val query = StringBuilder()
            if (SDK_INT >= 30 && uri != MediaType.Downloads.contentUri) {
                query.append("$IS_DOWNLOAD=0")
            }
            extraQuery?.let {
                if (query.isNotEmpty()) query.append(" AND ")
                query.append(it)
            }
            if (query.isNotEmpty()) putString(QUERY_ARG_SQL_SELECTION, query.toString())
        }
        val projection = if (SDK_INT >= 30) PROJECTION_29 + PROJECTION_30 else PROJECTION_29
        val cursor = contentResolver.query(uri, projection, extras, null)
        return ArrayList<MediaFile>(cursor?.count ?: 0).apply {
            cursor?.use { c ->
                while (c.moveToNext()) add(createMediaFile(c, uri))
            }
        }
    }

    internal fun getPath(uri: Uri): String? {
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        return contentResolver.query(uri, projection, null, null)?.use { c ->
            if (c.moveToNext()) "${c.getString(0)}/${c.getString(1)}".trimEnd('/')
            else null
        }
    }

    private fun createMediaFile(cursor: Cursor, queryUri: Uri): MediaFile {
        val mediaFile = MediaFile(
            uri = ContentUris.withAppendedId(queryUri, cursor.getLong(PROJECTION_ID)),
            dir = cursor.getString(PROJECTION_PATH),
            fileName = cursor.getString(PROJECTION_NAME),
            dateModified = cursor.getLongOrNull(PROJECTION_DATE_MODIFIED),
            generationModified = if (SDK_INT >= 30) cursor.getLongOrNull(
                PROJECTION_GENERATION_MODIFIED
            ) else null,
            size = cursor.getLong(PROJECTION_SIZE),
            isFavorite = if (SDK_INT >= 30) {
                cursor.getIntOrNull(PROJECTION_IS_FAVORITE) == 1
            } else false,
            ownerPackageName = cursor.getStringOrNull(PROJECTION_OWNER_PACKAGE_NAME),
            volume = cursor.getString(PROJECTION_VOLUME_NAME)
        )
        if (mediaFile.size == 0L) {
            // we can't trust this and it can cause OOMs if file is really big
            return mediaFile.copy(size = getRealSize(mediaFile))
        }
        return mediaFile
    }

    private fun getRealSize(mediaFile: MediaFile): Long {
        @Suppress("DEPRECATION")
        val extDir = Environment.getExternalStorageDirectory()
        val path = "$extDir/${mediaFile.dirPath}/${mediaFile.fileName}"
        return try {
            File(path).length()
        } catch (e: Exception) {
            Log.e("MediaScanner", "Error getting real size for $path", e)
            0L
        }
    }

}
