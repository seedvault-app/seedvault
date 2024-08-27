/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import com.stevesoltys.seedvault.plugins.webdav.DIRECTORY_ROOT
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.backends.webdav.WebDavBackend
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class WebDavStoragePlugin(
    /**
     * The result of Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
     */
    private val androidId: String,
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : StoragePlugin {

    private val topLevelFolder = TopLevelFolder("$androidId.sv")
    private val delegate = WebDavBackend(webDavConfig, root)

    @Throws(IOException::class)
    override suspend fun init() {
        // no-op
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
