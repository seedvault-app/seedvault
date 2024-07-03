/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.crypto.ChunkCrypto
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.scanner.FileScanner
import org.calyxos.backup.storage.scanner.FileScannerResult
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.time.Duration

internal class BackupResult(
    val chunkIds: Set<String>,
    val backupMediaFiles: List<BackupMediaFile>,
    val backupDocumentFiles: List<BackupDocumentFile>,
) {
    operator fun plus(other: BackupResult) = BackupResult(
        chunkIds = chunkIds + other.chunkIds,
        backupMediaFiles = backupMediaFiles + other.backupMediaFiles,
        backupDocumentFiles = backupDocumentFiles + other.backupDocumentFiles,
    )

    val isEmpty: Boolean = backupMediaFiles.isEmpty() && backupDocumentFiles.isEmpty()
}

internal class Backup(
    private val context: Context,
    private val db: Db,
    private val fileScanner: FileScanner,
    private val storagePluginGetter: () -> StoragePlugin,
    private val cacheRepopulater: ChunksCacheRepopulater,
    chunkSizeMax: Int = CHUNK_SIZE_MAX,
    private val streamCrypto: StreamCrypto = StreamCrypto,
) {

    companion object {
        const val VERSION: Byte = 0
        const val CHUNK_SIZE_MAX = 15 * 1024 * 1024
        const val SMALL_FILE_SIZE_MAX = 2 * 1024 * 1024
        private const val TAG = "Backup"
    }

    private val contentResolver = context.contentResolver
    private val storagePlugin get() = storagePluginGetter()
    private val filesCache = db.getFilesCache()
    private val chunksCache = db.getChunksCache()

    private val mac = try {
        ChunkCrypto.getMac(ChunkCrypto.deriveChunkIdKey(storagePlugin.getMasterKey()))
    } catch (e: GeneralSecurityException) {
        throw AssertionError(e)
    }
    private val streamKey = try {
        streamCrypto.deriveStreamKey(storagePlugin.getMasterKey())
    } catch (e: GeneralSecurityException) {
        throw AssertionError(e)
    }
    private val chunkWriter = ChunkWriter(streamCrypto, streamKey, chunksCache, storagePlugin)
    private val hasMediaAccessPerm =
        context.checkSelfPermission(ACCESS_MEDIA_LOCATION) == PERMISSION_GRANTED
    private val fileBackup = FileBackup(
        contentResolver = contentResolver,
        hasMediaAccessPerm = hasMediaAccessPerm,
        filesCache = filesCache,
        chunker = Chunker(mac, chunkSizeMax),
        chunkWriter = chunkWriter,
    )
    private val smallFileBackup = SmallFileBackup(
        contentResolver = context.contentResolver,
        hasMediaAccessPerm = hasMediaAccessPerm,
        filesCache = filesCache,
        zipChunker = ZipChunker(mac, chunkWriter),
    )

    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun runBackup(backupObserver: BackupObserver?) {
        backupObserver?.onStartScanning()
        var duration: Duration? = null
        try {
            // get available chunks, so we do not need to rely solely on local cache
            // for checking if a chunk already exists on storage
            val availableChunkIds = storagePlugin.getAvailableChunkIds().toHashSet()
            if (!chunksCache.areAllAvailableChunksCached(db, availableChunkIds)) {
                cacheRepopulater.repopulate(streamKey, availableChunkIds)
            }

            val scanResult = measure("Scanning") {
                fileScanner.getFiles()
            }
            val smallFiles = scanResult.smallFiles
            val largeFiles = scanResult.files
            val totalFiles = smallFiles.size + largeFiles.size
            val totalSize = smallFiles.sumOf { it.size } + largeFiles.sumOf { it.size }
            backupObserver?.onBackupStart(totalSize, totalFiles, smallFiles.size, largeFiles.size)

            // If a file's size changes so it moves into another size category,
            // it will get backed up normally again in the new category
            // with its old (unreferenced) chunks eventually deleted.
            // If (one of) its chunk(s) is missing, it will count as changed and chunked again.
            duration = measure {
                backupFiles(scanResult, availableChunkIds, backupObserver)
            }
            Log.e(TAG, "Changed files backup took $duration")
        } finally {
            backupObserver?.onBackupComplete(duration?.inWholeMilliseconds)
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun backupFiles(
        filesResult: FileScannerResult,
        availableChunkIds: HashSet<String>,
        backupObserver: BackupObserver?,
    ) {
        val startTime = System.currentTimeMillis()
        val numSmallFiles = filesResult.smallFiles.size
        val smallResult = measure("Backing up $numSmallFiles small files") {
            smallFileBackup.backupFiles(filesResult.smallFiles, availableChunkIds, backupObserver)
        }
        val numLargeFiles = filesResult.files.size
        val largeResult = measure("Backing up $numLargeFiles files") {
            fileBackup.backupFiles(filesResult.files, availableChunkIds, backupObserver)
        }
        val result = largeResult + smallResult
        if (result.isEmpty) return // TODO maybe warn user that nothing could get backed up?
        val backupSize = result.backupMediaFiles.sumOf { it.size } +
            result.backupDocumentFiles.sumOf { it.size }
        val endTime = System.currentTimeMillis()

        val backupSnapshot: BackupSnapshot
        val snapshotWriteTime = measure {
            backupSnapshot = BackupSnapshot.newBuilder()
                .setVersion(VERSION.toInt())
                .setName("Backup on ${Build.MANUFACTURER} ${Build.MODEL}")
                .addAllMediaFiles(result.backupMediaFiles)
                .addAllDocumentFiles(result.backupDocumentFiles)
                .setSize(backupSize)
                .setTimeStart(startTime)
                .setTimeEnd(endTime)
                .build()
            storagePlugin.getBackupSnapshotOutputStream(startTime).use { outputStream ->
                outputStream.write(VERSION.toInt())
                val ad = streamCrypto.getAssociatedDataForSnapshot(startTime)
                streamCrypto.newEncryptingStream(streamKey, outputStream, ad)
                    .use { encryptingStream ->
                        backupSnapshot.writeTo(encryptingStream)
                    }
            }
        }
        val snapshotSize = backupSnapshot.serializedSize.toLong()
        val snapshotSizeStr = Formatter.formatShortFileSize(context, snapshotSize)
        Log.e(TAG, "Creating/writing snapshot took $snapshotWriteTime ($snapshotSizeStr)")

        val chunkIncrementTime = measure {
            db.applyInParts(result.chunkIds) {
                chunksCache.incrementRefCount(it)
            }
        }
        Log.e(TAG, "Incrementing chunk ref counters took $chunkIncrementTime")

        // updating the lastSeen time only here is an optimization (faster than file by file)
        // which we need to remember when purging old files from the cache
        val lastSeenUpdateTime = measure {
            val uris = filesResult.smallFiles.map { it.uri } + filesResult.files.map { it.uri }
            db.applyInParts(uris) {
                filesCache.updateLastSeen(it)
            }
        }
        Log.e(TAG, "Updating last seen time took $lastSeenUpdateTime")
    }

}
