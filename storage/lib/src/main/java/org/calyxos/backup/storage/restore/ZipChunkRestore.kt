/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import android.util.Log
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

private const val TAG = "ZipChunkRestore"

@Suppress("BlockingMethodInNonBlockingContext")
internal class ZipChunkRestore(
    storagePlugin: StoragePlugin,
    fileRestore: FileRestore,
    streamCrypto: StreamCrypto,
    streamKey: ByteArray,
) : AbstractChunkRestore(storagePlugin, fileRestore, streamCrypto, streamKey) {

    /**
     * Assumes that files in [zipChunks] are sorted by zipIndex with no duplicate indices.
     */
    suspend fun restore(
        version: Int,
        storedSnapshot: StoredSnapshot,
        zipChunks: Collection<RestorableChunk>,
        observer: RestoreObserver?,
    ): Int {
        var restoredFiles = 0
        zipChunks.forEach { zipChunk ->
            try {
                getAndDecryptChunk(version, storedSnapshot, zipChunk.chunkId) { decryptedStream ->
                    restoredFiles += restoreZipChunk(zipChunk, decryptedStream, observer)
                }
            } catch (e: Exception) {
                // we also swallow exceptions in [restoreZipChunk]
                // so should only get something from [getAndDecryptChunk] here.
                Log.e(TAG, "Failed to decrypt chunk ${zipChunk.chunkId}", e)
                zipChunk.files.forEach { file ->
                    observer?.onFileRestoreError(file, e)
                }
                // we try to continue to restore as many files as possible
            }
        }
        return restoredFiles
    }

    private suspend fun restoreZipChunk(
        zipChunk: RestorableChunk,
        decryptedStream: InputStream,
        observer: RestoreObserver?,
    ): Int {
        var restoredFiles = 0
        ZipInputStream(decryptedStream).use { zip ->
            zipChunk.files.forEach { file ->
                try {
                    restoreZipEntry(zip, file, observer)
                    restoredFiles++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore zip entry for ${file.path}", e)
                    observer?.onFileRestoreError(file, e)
                    // we try to continue to restore as many files as possible
                }
            }
        }
        return restoredFiles
    }

    @Throws(IOException::class)
    private suspend fun restoreZipEntry(
        zip: ZipInputStream,
        file: RestorableFile,
        observer: RestoreObserver?,
    ) {
        var entry = zip.nextEntry
        while (entry != null && entry.name != file.zipIndex.toString()) {
            entry = zip.nextEntry
        }
        check(entry != null) { "zip entry was null for: $file" }
        restoreFile(file, observer, "S") { outputStream: OutputStream ->
            val bytes = zip.copyTo(outputStream)
            zip.closeEntry()
            bytes
        }
    }

}
