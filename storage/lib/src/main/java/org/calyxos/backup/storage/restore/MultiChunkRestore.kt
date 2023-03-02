/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import android.content.Context
import android.util.Log
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

private const val TAG = "MultiChunkRestore"

@Suppress("BlockingMethodInNonBlockingContext")
internal class MultiChunkRestore(
    private val context: Context,
    storagePlugin: StoragePlugin,
    fileRestore: FileRestore,
    streamCrypto: StreamCrypto,
    streamKey: ByteArray,
) : AbstractChunkRestore(storagePlugin, fileRestore, streamCrypto, streamKey) {

    suspend fun restore(
        version: Int,
        storedSnapshot: StoredSnapshot,
        chunkMap: Map<String, RestorableChunk>,
        files: Collection<RestorableFile>,
        observer: RestoreObserver?,
    ): Int {
        var restoredFiles = 0
        files.forEach { file ->
            try {
                restoreFile(file, observer, "L") { outputStream ->
                    writeChunks(version, storedSnapshot, file, chunkMap, outputStream)
                }
                restoredFiles++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore single chunk file ${file.path}", e)
                observer?.onFileRestoreError(file, e)
                // we try to continue to restore as many files as possible
            }
        }
        return restoredFiles
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun writeChunks(
        version: Int,
        storedSnapshot: StoredSnapshot,
        file: RestorableFile,
        chunkMap: Map<String, RestorableChunk>,
        outputStream: OutputStream,
    ): Long {
        var bytes = 0L
        file.chunkIds.forEach { chunkId ->
            val otherFiles =
                chunkMap[chunkId]?.files ?: error("chunk id $chunkId not in map")
            val chunkWriter: suspend (InputStream) -> Unit = { decryptedStream ->
                bytes += decryptedStream.copyTo(outputStream)
            }
            val isCached = isCached(chunkId)
            if (isCached || otherFiles.size > 1) {
                getAndCacheChunk(version, storedSnapshot, chunkId, chunkWriter)
            } else {
                getAndDecryptChunk(version, storedSnapshot, chunkId, chunkWriter)
            }

            otherFiles.remove(file)
            if (isCached && otherFiles.isEmpty()) removeCachedChunk(chunkId)
        }
        return bytes
    }

    private fun isCached(chunkId: String): Boolean {
        return getChunkCacheFile(chunkId).isFile
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun getAndCacheChunk(
        version: Int,
        storedSnapshot: StoredSnapshot,
        chunkId: String,
        streamReader: suspend (InputStream) -> Unit,
    ) {
        val file = getChunkCacheFile(chunkId)
        if (!file.isFile) {
            FileOutputStream(file).use { outputStream ->
                getAndDecryptChunk(version, storedSnapshot, chunkId) { decryptedStream ->
                    decryptedStream.copyTo(outputStream)
                }
            }
        }
        FileInputStream(file).use { inputStream ->
            streamReader(inputStream)
        }
    }

    private fun removeCachedChunk(chunkId: String) {
        getChunkCacheFile(chunkId).delete()
    }

    private fun getChunkCacheFile(chunkId: String) = File(context.cacheDir, chunkId)

}
