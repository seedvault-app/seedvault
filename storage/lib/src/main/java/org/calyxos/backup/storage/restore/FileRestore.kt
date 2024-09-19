/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment.getExternalStorageDirectory
import android.provider.MediaStore.MediaColumns
import android.util.Log
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.scanner.MediaScanner
import org.calyxos.seedvault.core.backends.saf.openOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

private const val TAG = "FileRestore"

internal class FileRestore(
    private val context: Context,
    private val mediaScanner: MediaScanner,
) {

    private val contentResolver = context.contentResolver

    @Throws(IOException::class)
    suspend fun restoreFile(
        file: RestorableFile,
        observer: RestoreObserver?,
        tag: String,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ) {
        val bytes: Long
        val finalTag: String
        when {
            file.mediaFile != null -> {
                bytes = restoreFile(file.mediaFile, streamWriter)
                finalTag = "M$tag"
            }

            file.docFile != null -> {
                bytes = restoreFile(file, streamWriter)
                finalTag = "D$tag"
            }

            else -> {
                error("unexpected file: $file")
            }
        }
        observer?.onFileRestored(file, bytes, finalTag)
    }

    @Throws(IOException::class)
    private suspend fun restoreFile(
        docFile: RestorableFile,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ): Long {
        // ensure directory exists
        val dir = File("${getExternalStorageDirectory()}/${docFile.dir}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Could not create ${dir.absolutePath}")
        }
        var file = File(dir, docFile.name)
        // TODO should we also calculate and check the chunk IDs?
        if (file.isFile && file.length() == docFile.size &&
            file.lastModified() == docFile.lastModified
        ) {
            Log.i(TAG, "Not restoring $file, already there unchanged.")
            return file.length() // not restoring existing file with same length and date
        } else {
            var i = 0
            // don't overwrite existing files, if they exist
            while (file.exists()) { // find non-existing file-name
                i++
                val lastDot = docFile.name.lastIndexOf('.')
                val newName = if (lastDot == -1) "${docFile.name} ($i)"
                else docFile.name.replaceRange(lastDot..lastDot, " ($i).")
                file = File(dir, newName)
            }
            val bytesWritten = try {
                // copy chunk(s) into file
                file.outputStream().use { outputStream ->
                    streamWriter(outputStream)
                }
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            // re-set lastModified timestamp
            file.setLastModified(docFile.lastModified ?: 0)

            // This might be a media file, so do we need to index it.
            // Otherwise things like a wrong size of 0 bytes in MediaStore can happen.
            indexFile(file)

            return bytesWritten
        }
    }

    @Throws(IOException::class)
    private suspend fun restoreFile(
        mediaFile: BackupMediaFile,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ): Long {
        // TODO should we also calculate and check the chunk IDs?
        if (mediaScanner.existsMediaFileUnchanged(mediaFile)) {
            Log.i(
                TAG,
                "Not restoring ${mediaFile.path}/${mediaFile.name}, already there unchanged."
            )
            return mediaFile.size
        }
        // Insert pending media item into MediaStore
        val contentValues = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, mediaFile.name)
            put(MediaColumns.RELATIVE_PATH, mediaFile.path)
            put(MediaColumns.IS_PENDING, 1)
            put(MediaColumns.IS_FAVORITE, if (mediaFile.isFavorite) 1 else 0)
        }
        val contentUri = MediaType.fromBackupMediaType(mediaFile.type).contentUri
        val uri = contentResolver.insert(contentUri, contentValues)!!

        // copy backed up file into new item
        val bytesWritten = uri.openOutputStream(contentResolver).use { outputStream ->
            streamWriter(outputStream)
        }

        // after write, set it to non-pending (renames file to final name)
        contentValues.clear()
        contentValues.apply {
            put(MediaColumns.IS_PENDING, 0)
            // changing owner requires backup permission
            // done here because we are not allowed to access pending media we don't own
            put(MediaColumns.OWNER_PACKAGE_NAME, mediaFile.ownerPackageName)
        }
        try {
            contentResolver.update(uri, contentValues, null, null)
        } catch (e: IllegalStateException) {
            // can happen if there's close to 100 files with the same name and (??) suffix already
            if (e.message?.startsWith("Failed to build unique file") == true) {
                // try again with a changed the file name
                val name = " [${Random.nextInt(Int.MAX_VALUE)}]-${mediaFile.name}"
                contentValues.put(MediaColumns.DISPLAY_NAME, name)
                contentResolver.update(uri, contentValues, null, null)
            } else throw e
        }

        setLastModifiedOnMediaFile(mediaFile, uri)

        return bytesWritten
    }

    private fun setLastModifiedOnMediaFile(mediaFile: BackupMediaFile, uri: Uri) {
        val extDir = getExternalStorageDirectory()

        // re-set lastModified as we can't use the MediaStore for this (read-only property)
        val path = "$extDir/${mediaFile.path}/${mediaFile.name}"
        val file = File(path)
        if (file.isFile) {
            file.setLastModified(mediaFile.lastModified)
            // update lastModified in MediaStore
            indexFile(file)
        } else {
            // file does not exist, probably because it was renamed on restore
            // so try to find it in MediaStore
            val relPath = mediaScanner.getPath(uri)
            if (relPath == null) {
                Log.w(TAG, "Did not find $path with $uri, can't set lastModified")
            } else {
                val newPath = "$extDir/$relPath"
                val newFile = File(newPath)
                Log.w(TAG, "WARNING: ${mediaFile.name} is now ${newFile.path}")
                if (newFile.isFile) {
                    newFile.setLastModified(mediaFile.lastModified)
                    // update lastModified in MediaStore
                    indexFile(newFile)
                } else {
                    Log.e(TAG, "Could not setLastModified on ${newFile.absolutePath}")
                }
            }
        }
    }

    private fun indexFile(file: File) {
        // Intent(ACTION_MEDIA_SCANNER_SCAN_FILE) is deprecated
        // and does not pick up lastModified changes done in file-system
        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
    }

}
