/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.content.ContentResolver
import android.util.Log
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.content.ContentFile
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.content.MediaFile
import org.calyxos.backup.storage.db.CachedFile
import org.calyxos.backup.storage.db.FilesCache
import org.calyxos.backup.storage.openInputStream
import java.io.IOException
import java.security.GeneralSecurityException

@Suppress("BlockingMethodInNonBlockingContext")
internal class SmallFileBackup(
    private val contentResolver: ContentResolver,
    private val filesCache: FilesCache,
    private val zipChunker: ZipChunker,
    private val hasMediaAccessPerm: Boolean,
) {

    companion object {
        private const val TAG = "SmallFileBackup"
    }

    suspend fun backupFiles(
        files: List<ContentFile>,
        availableChunkIds: HashSet<String>,
        backupObserver: BackupObserver?,
    ): BackupResult {
        val chunkIds = HashSet<String>()
        val missingChunkIds = ArrayList<String>()
        val backupMediaFiles = ArrayList<BackupMediaFile>()
        val backupDocumentFiles = ArrayList<BackupDocumentFile>()
        var bytesWritten = 0L
        var zipChunks = 0

        val changedFiles = files.filter { file ->
            val cachedFile = filesCache.getByUri(file.uri)
            val fileMissingChunkIds = cachedFile?.chunks?.minus(availableChunkIds) ?: emptyList()
            missingChunkIds.addAll(fileMissingChunkIds)
            if (fileMissingChunkIds.isEmpty() && file.hasNotChanged(cachedFile)) {
                Log.d(TAG, "File has NOT changed: ${file.fileName}")
                cachedFile as CachedFile // not null because hasNotChanged() returned true
                if (file is MediaFile) {
                    val backupFile = file.toBackupFile(cachedFile.chunks, cachedFile.zipIndex)
                    backupMediaFiles.add(backupFile)
                }
                if (file is DocFile) {
                    val backupFile = file.toBackupFile(cachedFile.chunks, cachedFile.zipIndex)
                    backupDocumentFiles.add(backupFile)
                }
                if (chunkIds.add(cachedFile.chunks[0])) zipChunks++
                backupObserver?.onFileBackedUp(file, false, 0, 0, "S")
                false // filter out
            } else true
        }
        changedFiles.windowed(2, 1, true).forEach { window ->
            val file = window[0]
            val result = try {
                makeZipChunk(window, missingChunkIds)
            } catch (e: IOException) {
                backupObserver?.onFileBackupError(file, "S")
                Log.e(TAG, "Error backing up ${file.uri}", e)
                // Continuing here will ignore the file.
                // There's a risk that the next file will exceed max zip chunk size,
                // but as all files here are supposed to be small and the size limit isn't enforced,
                // we accept this risk for now.
                return@forEach
            }
            if (result == null) {
                // null means file will be added to zip chunk
                backupObserver?.onFileBackedUp(file, true, 0, 0, "S")
                return@forEach
            }
            backupMediaFiles.addAll(result.backupMediaFiles)
            backupDocumentFiles.addAll(result.backupDocumentFiles)
            if (chunkIds.add(result.chunkId)) zipChunks++
            bytesWritten += result.bytesWritten

            backupObserver?.onFileBackedUp(file, result.hasChanged, 0, result.bytesWritten, "S")
        }
        Log.d(TAG, "Zip Chunks: $zipChunks")
        return BackupResult(chunkIds, backupMediaFiles, backupDocumentFiles)
    }

    private class SmallFileBackupResult(
        val chunkId: String,
        val backupMediaFiles: List<BackupMediaFile>,
        val backupDocumentFiles: List<BackupDocumentFile>,
        val bytesWritten: Long,
        val hasChanged: Boolean,
    )

    /**
     * Adds the first file of the given window to a [ZipChunk].
     *
     * @param window a list of exactly one or two [ContentFile]s.
     *
     * @return [SmallFileBackupResult] if the chunk is full
     * or the window contained only a single (last) file.
     * Returns null, if there's space in the zip chunk and the next file can be added.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun makeZipChunk(
        window: List<ContentFile>,
        missingChunkIds: List<String>,
    ): SmallFileBackupResult? {
        val file = window[0]
        val nextFile = window.getOrNull(1)

        // add file content to zip chunk
        val uri = file.getOriginalUri(hasMediaAccessPerm)
        uri.openInputStream(contentResolver).use { inputStream ->
            zipChunker.addFile(file, inputStream)
        }
        return if (nextFile == null || !zipChunker.fitsFile(nextFile)) {
            // Close zip chunk, if the next file would not fit in any more (or this is the last)
            finalizeAndReset(zipChunker, missingChunkIds)
        } else null
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun finalizeAndReset(
        zipChunker: ZipChunker,
        missingChunkIds: List<String>,
    ): SmallFileBackupResult {
        val zipChunk = zipChunker.finalizeAndReset(missingChunkIds)
        val chunkIds = listOf(zipChunk.id)
        val backupMediaFiles = ArrayList<BackupMediaFile>()
        val backupDocumentFiles = ArrayList<BackupDocumentFile>()

        zipChunk.files.forEachIndexed { index, file ->
            // Attention: don't modify the same file concurrently or this will cause bugs
            filesCache.upsert(file.toCachedFile(chunkIds, index + 1))
            when (file) {
                is MediaFile -> backupMediaFiles.add(file.toBackupFile(chunkIds, index + 1))
                is DocFile -> backupDocumentFiles.add(file.toBackupFile(chunkIds, index + 1))
            }
        }
        return SmallFileBackupResult(
            chunkId = zipChunk.id,
            backupMediaFiles = backupMediaFiles,
            backupDocumentFiles = backupDocumentFiles,
            bytesWritten = if (zipChunk.wasUploaded) zipChunk.size else 0L,
            hasChanged = zipChunk.wasUploaded,
        )
    }

}
