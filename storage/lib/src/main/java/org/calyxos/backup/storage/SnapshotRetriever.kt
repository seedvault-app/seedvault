/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage

import com.google.protobuf.InvalidProtocolBufferException
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.restore.readVersion
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.io.IOException
import java.security.GeneralSecurityException

internal class SnapshotRetriever(
    private val backendGetter: () -> Backend,
    private val streamCrypto: StreamCrypto = StreamCrypto,
) {

    @Throws(
        IOException::class,
        GeneralSecurityException::class,
        InvalidProtocolBufferException::class,
    )
    suspend fun getSnapshot(streamKey: ByteArray, storedSnapshot: StoredSnapshot): BackupSnapshot {
        return backendGetter().load(storedSnapshot.snapshotHandle).use { inputStream ->
            val version = inputStream.readVersion()
            val timestamp = storedSnapshot.timestamp
            val ad = streamCrypto.getAssociatedDataForSnapshot(timestamp, version.toByte())
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                BackupSnapshot.parseFrom(decryptedStream)
            }
        }
    }

}

@Throws(IOException::class)
internal suspend fun Backend.getCurrentBackupSnapshots(androidId: String): List<StoredSnapshot> {
    val topLevelFolder = TopLevelFolder("$androidId.sv")
    val snapshots = ArrayList<StoredSnapshot>()
    list(topLevelFolder, FileBackupFileType.Snapshot::class) { fileInfo ->
        val handle = fileInfo.fileHandle as FileBackupFileType.Snapshot
        val folderName = handle.topLevelFolder.name
        val timestamp = handle.time
        val storedSnapshot = StoredSnapshot(folderName, timestamp)
        snapshots.add(storedSnapshot)
    }
    return snapshots
}

@Throws(IOException::class)
internal suspend fun Backend.getBackupSnapshotsForRestore(): List<StoredSnapshot> {
    val snapshots = ArrayList<StoredSnapshot>()
    list(null, FileBackupFileType.Snapshot::class) { fileInfo ->
        val handle = fileInfo.fileHandle as FileBackupFileType.Snapshot
        val folderName = handle.topLevelFolder.name
        val timestamp = handle.time
        val storedSnapshot = StoredSnapshot(folderName, timestamp)
        snapshots.add(storedSnapshot)
    }
    return snapshots
}
