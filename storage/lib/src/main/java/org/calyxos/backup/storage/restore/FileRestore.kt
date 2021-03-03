package org.calyxos.backup.storage.restore

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Environment.getExternalStorageDirectory
import android.provider.MediaStore.MediaColumns
import android.util.Log
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.openOutputStream
import org.calyxos.backup.storage.scanner.MediaScanner
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

private const val TAG = "FileRestore"

@Suppress("BlockingMethodInNonBlockingContext")
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
                bytes = if (SDK_INT < 30) {
                    // MediaProvider on API 29 doesn't let us write files into any folders freely,
                    // so don't attempt to restore via MediaStore API
                    restoreFile(file, streamWriter)
                } else {
                    restoreFile(file.mediaFile, streamWriter)
                }
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
        @Suppress("DEPRECATION")
        val dir = File("${getExternalStorageDirectory()}/${docFile.dir}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Could not create ${dir.absolutePath}")
        }
        // find non-existing file-name
        var file = File(dir, docFile.name)
        var i = 0
        // we don't support existing files, but at least don't overwrite them when they do exist
        while (file.exists()) {
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

    @Throws(IOException::class)
    private suspend fun restoreFile(
        mediaFile: BackupMediaFile,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ): Long {
        // Insert pending media item into MediaStore
        val contentValues = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, mediaFile.name)
            put(MediaColumns.RELATIVE_PATH, mediaFile.path)
            // changing owner requires backup permission
            put(MediaColumns.OWNER_PACKAGE_NAME, mediaFile.ownerPackageName)
            put(MediaColumns.IS_PENDING, 1)
            if (SDK_INT >= 30) {
                val isFavorite = if (mediaFile.isFavorite) 1 else 0
                put(MediaColumns.IS_FAVORITE, isFavorite)
            }
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
        @Suppress("DEPRECATION")
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
