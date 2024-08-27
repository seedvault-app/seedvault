/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.plugin.saf

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.backends.saf.SafBackend
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * @param appContext application context provided by the storage module
 */
public abstract class SafStoragePlugin(
    private val appContext: Context,
) : StoragePlugin {
    protected abstract val delegate: SafBackend

    private val androidId: String by lazy {
        @SuppressLint("HardwareIds")
        // This is unique to each combination of app-signing key, user, and device
        // so we don't leak anything by not hashing this and can use it as is.
        // Note: Use [appContext] here to not get the wrong ID for a different user.
        val androidId = Settings.Secure.getString(appContext.contentResolver, ANDROID_ID)
        androidId
    }
    private val topLevelFolder: TopLevelFolder by lazy {
        // the folder name is our user ID
        val folderName = "$androidId.sv"
        TopLevelFolder(folderName)
    }

    override suspend fun init() {
        // no-op as we are getting [root] created from super class
    }

    @Throws(IOException::class)
    override suspend fun getAvailableChunkIds(): List<String> {
        val chunkIds = ArrayList<String>()
        delegate.list(topLevelFolder, FileBackupFileType.Blob::class) { fileInfo ->
            chunkIds.add(fileInfo.fileHandle.name)
        }
        return chunkIds
    }

    @Throws(IOException::class)
    override suspend fun getChunkOutputStream(chunkId: String): OutputStream {
        val fileHandle = FileBackupFileType.Blob(androidId, chunkId)
        return delegate.save(fileHandle)
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        val fileHandle = FileBackupFileType.Snapshot(androidId, timestamp)
        return delegate.save(fileHandle)
    }

    /************************* Restore *******************************/

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotsForRestore(): List<StoredSnapshot> {
        val snapshots = ArrayList<StoredSnapshot>()
        delegate.list(null, FileBackupFileType.Snapshot::class) { fileInfo ->
            val handle = fileInfo.fileHandle as FileBackupFileType.Snapshot
            val folderName = handle.topLevelFolder.name
            val timestamp = handle.time
            val storedSnapshot = StoredSnapshot(folderName, timestamp)
            snapshots.add(storedSnapshot)
        }
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotInputStream(storedSnapshot: StoredSnapshot): InputStream {
        val androidId = storedSnapshot.androidId
        val handle = FileBackupFileType.Snapshot(androidId, storedSnapshot.timestamp)
        return delegate.load(handle)
    }

    @Throws(IOException::class)
    override suspend fun getChunkInputStream(
        snapshot: StoredSnapshot,
        chunkId: String,
    ): InputStream {
        val handle = FileBackupFileType.Blob(snapshot.androidId, chunkId)
        return delegate.load(handle)
    }

    /************************* Pruning *******************************/

    @Throws(IOException::class)
    override suspend fun getCurrentBackupSnapshots(): List<StoredSnapshot> {
        val snapshots = ArrayList<StoredSnapshot>()
        delegate.list(topLevelFolder, FileBackupFileType.Snapshot::class) { fileInfo ->
            val handle = fileInfo.fileHandle as FileBackupFileType.Snapshot
            val folderName = handle.topLevelFolder.name
            val timestamp = handle.time
            val storedSnapshot = StoredSnapshot(folderName, timestamp)
            snapshots.add(storedSnapshot)
        }
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun deleteBackupSnapshot(storedSnapshot: StoredSnapshot) {
        val androidId = storedSnapshot.androidId
        val handle = FileBackupFileType.Snapshot(androidId, storedSnapshot.timestamp)
        delegate.remove(handle)
    }

    @Throws(IOException::class)
    override suspend fun deleteChunks(chunkIds: List<String>) {
        chunkIds.forEach { chunkId ->
            val androidId = topLevelFolder.name.substringBefore(".sv")
            val handle = FileBackupFileType.Blob(androidId, chunkId)
            delegate.remove(handle)
        }
    }

}
