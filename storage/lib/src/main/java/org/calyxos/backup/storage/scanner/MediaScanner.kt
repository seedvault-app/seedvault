/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.scanner

import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION
import android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns.IS_DOWNLOAD
import android.util.Log
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.content.MediaFile
import java.io.File

public class MediaScanner(context: Context) {

    private companion object {
        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.VOLUME_NAME,
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
            val query = StringBuilder().apply {
                // don't include directories (if they are non-empty they will be in implicitly)
                append("${MediaStore.MediaColumns.MIME_TYPE}!='$MIME_TYPE_DIR'")
            }
            if (uri != MediaType.Downloads.contentUri) {
                query.append(" AND $IS_DOWNLOAD=0")
            }
            extraQuery?.let {
                query.append(" AND ")
                query.append(it)
            }
            putString(QUERY_ARG_SQL_SELECTION, query.toString())
        }
        val cursor = contentResolver.query(uri, PROJECTION, extras, null)
        return ArrayList<MediaFile>(cursor?.count ?: 0).apply {
            cursor?.use { c ->
                while (c.moveToNext()) add(createMediaFile(c, uri))
            }
        }
    }

    internal fun existsMediaFileUnchanged(mediaFile: BackupMediaFile): Boolean {
        val uri = MediaType.fromBackupMediaType(mediaFile.type).contentUri
        val extras = Bundle().apply {
            // search for files with same path and name
            val query = StringBuilder().apply {
                append("${MediaStore.MediaColumns.MIME_TYPE}!='$MIME_TYPE_DIR'")
                append(" AND ")
                append("${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                append(" AND ")
                append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            }
            putString(QUERY_ARG_SQL_SELECTION, query.toString())
            val args = arrayOf(
                mediaFile.path + "/", // Note trailing slash that is important
                mediaFile.name,
            )
            putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, args)
        }

        contentResolver.query(uri, PROJECTION, extras, null)?.use { c ->
            while (c.moveToNext()) {
                val f = createMediaFile(c, uri)
                // note that we get seconds, but store milliseconds
                if (f.dateModified == mediaFile.lastModified / 1000 && f.size == mediaFile.size) {
                    return true
                }
            }
        }
        return false
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
            generationModified = cursor.getLongOrNull(PROJECTION_GENERATION_MODIFIED),
            size = cursor.getLong(PROJECTION_SIZE),
            isFavorite = cursor.getIntOrNull(PROJECTION_IS_FAVORITE) == 1,
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
