/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.isTreeUri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.SnapshotRetriever
import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.backup.ChunksCacheRepopulater
import org.calyxos.backup.storage.check.Checker
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.getCurrentBackupSnapshots
import org.calyxos.backup.storage.getMediaType
import org.calyxos.backup.storage.prune.Pruner
import org.calyxos.backup.storage.prune.RetentionManager
import org.calyxos.backup.storage.restore.FileRestore
import org.calyxos.backup.storage.restore.Restore
import org.calyxos.backup.storage.scanner.DocumentScanner
import org.calyxos.backup.storage.scanner.FileScanner
import org.calyxos.backup.storage.scanner.MediaScanner
import org.calyxos.backup.storage.toStoredUri
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.IBackendManager
import org.calyxos.seedvault.core.backends.saf.getDocumentPath
import org.calyxos.seedvault.core.crypto.KeyManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StorageBackup"

public class StorageBackup(
    private val context: Context,
    private val backendManager: IBackendManager,
    private val keyManager: KeyManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val db: Db by lazy {
        Db.build(context)
    }
    private val uriStore by lazy { db.getUriStore() }
    private val backend get() = backendManager.backend

    @SuppressLint("HardwareIds")
    private val androidId = Settings.Secure.getString(context.contentResolver, ANDROID_ID)

    private val mediaScanner by lazy { MediaScanner(context) }
    private val snapshotRetriever = SnapshotRetriever(backendManager)
    private val chunksCacheRepopulater = ChunksCacheRepopulater(
        chunksCache = db.getChunksCache(),
        backendManager = backendManager,
        androidId = androidId,
        snapshotRetriever = snapshotRetriever,
    )
    private val backup by lazy {
        val documentScanner = DocumentScanner(context)
        val fileScanner = FileScanner(uriStore, mediaScanner, documentScanner)
        Backup(
            context = context,
            db = db,
            fileScanner = fileScanner,
            backendManager = backendManager,
            androidId = androidId,
            keyManager = keyManager,
            cacheRepopulater = chunksCacheRepopulater
        )
    }
    private val restore by lazy {
        val fileRestore = FileRestore(context, mediaScanner)
        Restore(context, backendManager, keyManager, snapshotRetriever, fileRestore)
    }
    private val retention = RetentionManager(context)
    private val pruner by lazy {
        Pruner(db, retention, backendManager, androidId, keyManager, snapshotRetriever)
    }
    private val checker by lazy {
        Checker(
            db = db,
            backendManager = backendManager,
            snapshotRetriever = snapshotRetriever,
            keyManager = keyManager,
            cacheRepopulater = chunksCacheRepopulater,
            androidId = androidId,
        )
    }

    private val backupRunning = AtomicBoolean(false)
    private val restoreRunning = AtomicBoolean(false)
    private val checkRunning = AtomicBoolean(false)

    public var checkResult: CheckResult? = null
        private set

    public val uris: Set<Uri>
        @WorkerThread
        get() {
            return uriStore.getStoredUris().map { it.uri }.toSet()
        }

    @Throws(IllegalArgumentException::class)
    public suspend fun addUri(uri: Uri): Unit = withContext(dispatcher) {
        if (uri.authority == MediaStore.AUTHORITY) {
            if (uri !in mediaUris) throw IllegalArgumentException("Not a supported MediaStore URI")
        } else if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            if (!isTreeUri(uri)) throw IllegalArgumentException("Not a tree URI")
        } else {
            throw IllegalArgumentException()
        }
        Log.e(TAG, "Adding URI $uri")
        uriStore.addStoredUri(uri.toStoredUri())
    }

    public suspend fun removeUri(uri: Uri): Unit = withContext(dispatcher) {
        Log.e(TAG, "Removing URI $uri")
        uriStore.removeStoredUri(uri.toStoredUri())
    }

    public suspend fun getUriSummaryString(): String = withContext(dispatcher) {
        val uris = uris.sortedDescending()
        val list = ArrayList<String>()
        for (uri in uris) {
            val nameRes = uri.getMediaType()?.nameRes
            if (nameRes == null) {
                uri.getDocumentPath()?.let { list.add(it) }
            } else {
                list.add(context.getString(nameRes))
            }
        }
        list.joinToString(", ", limit = 5)
    }

    /**
     * Ensures the storage is set-up to receive backups and deletes all snapshots
     * (see [deleteAllSnapshots]) as well as clears local cache (see [clearCache]).
     */
    public suspend fun init() {
        deleteAllSnapshots()
        clearCache()
    }

    /**
     * Run this on a new storage location to ensure that there are no old snapshots
     * (potentially encrypted with an old key) laying around.
     * Using a storage location with existing data is not supported.
     * Using the same root folder for storage on different devices or user profiles is fine though
     * as the [Backend] should isolate storage per [StoredSnapshot.userId].
     */
    private suspend fun deleteAllSnapshots(): Unit = withContext(dispatcher) {
        try {
            backend.getCurrentBackupSnapshots(androidId).forEach {
                val handle = FileBackupFileType.Snapshot(androidId, it.timestamp)
                try {
                    // TODO could we not only delete snapshots that we cannot decrypt here?
                    //  ChunksCacheRepopulater should be able to restore refCounts
                    backend.remove(handle)
                } catch (e: IOException) {
                    Log.e(TAG, "Error deleting snapshot $it", e)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error deleting all snapshots", e)
        }
    }

    /**
     * It is advised to clear existing cache when selecting a new storage location.
     */
    public suspend fun clearCache(): Unit = withContext(dispatcher) {
        db.getChunksCache().clear()
        db.getFilesCache().clear()
    }

    public suspend fun runBackup(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            if (checkRunning.get() || !backupRunning.compareAndSet(false, true)) {
                Log.w(TAG, "Backup or check already running, not starting a new one")
                return@withContext false
            }
            try {
                backup.runBackup(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during backup", e)
                false
            } finally {
                backupRunning.set(false)
            }
        }

    /**
     * Sets how many backup snapshots to keep in storage when running [pruneOldBackups].
     *
     * @throws IllegalArgumentException if all retention values are set to 0.
     */
    public fun setSnapshotRetention(snapshotRetention: SnapshotRetention) {
        retention.setSnapshotRetention(snapshotRetention)
    }

    /**
     * Gets the current snapshot retention policy.
     */
    @WorkerThread
    public fun getSnapshotRetention(): SnapshotRetention = retention.getSnapshotRetention()

    /**
     * Prunes old backup snapshots according to the parameters set via [setSnapshotRetention].
     * This will delete backed up data. Use with care!
     *
     * Run this only after [runBackup] returns true to ensure
     * that no chunks from partial backups get removed and need to be re-uploaded.
     */
    public suspend fun pruneOldBackups(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            backupObserver?.onPruneStartScanning()
            try {
                pruner.prune(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during pruning backups", e)
                backupObserver?.onPruneError(null, e)
                false
            }
        }

    public fun getBackupSnapshots(): Flow<SnapshotResult> {
        return restore.getBackupSnapshots()
    }

    public suspend fun restoreBackupSnapshot(
        storedSnapshot: StoredSnapshot,
        snapshot: BackupSnapshot,
        restoreObserver: RestoreObserver? = null,
    ): Boolean = withContext(dispatcher) {
        if (!restoreRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Restore already running, not starting a new one")
            return@withContext false
        }
        try {
            restore.restoreBackupSnapshot(storedSnapshot, snapshot, restoreObserver)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore", e)
            false
        } finally {
            restoreRunning.set(false)
        }
    }

    public fun getBackupSize(): Long {
        return checker.getBackupSize()
    }

    public suspend fun checkBackups(percent: Int, checkObserver: CheckObserver?): Boolean {
        check(percent in 0..100) { "Invalid percentage: $percent" }
        if (checkRunning.get() || backupRunning.get()) {
            Log.w(TAG, "Check or backup already running, not starting a new one")
            return false
        }
        checkResult = withContext(dispatcher) {
            checkRunning.set(true)
            try {
                checkObserver?.onStartChecking()
                checker.check(percent, checkObserver)
            } finally {
                checkRunning.set(false)
            }
        }
        return true
    }

    public fun clearCheckResult() {
        Log.i(TAG, "Clearing check result...")
        checkResult = null
    }

}
