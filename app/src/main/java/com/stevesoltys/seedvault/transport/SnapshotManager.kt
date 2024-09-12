/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport

import com.github.luben.zstd.ZstdOutputStream
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.transport.restore.Loader
import io.github.oshai.kotlinlogging.KotlinLogging
import okio.Buffer
import okio.buffer
import okio.sink
import org.calyxos.seedvault.core.backends.AppBackupFileType

internal class SnapshotManager(
    private val crypto: Crypto,
    private val loader: Loader,
    private val backendManager: BackendManager,
) {

    private val log = KotlinLogging.logger {}

    /**
     * The latest [Snapshot]. May be stale if [onSnapshotsLoaded] has not returned
     * or wasn't called since new snapshots have been created.
     */
    var latestSnapshot: Snapshot? = null
        private set

    suspend fun onSnapshotsLoaded(handles: List<AppBackupFileType.Snapshot>): List<Snapshot> {
        return handles.map { snapshotHandle ->
            // TODO set up local snapshot cache, so we don't need to download those all the time
            // TODO is it a fatal error when one snapshot is corrupted or couldn't get loaded?
            val snapshot = loader.loadFile(snapshotHandle).use { inputStream ->
                Snapshot.parseFrom(inputStream)
            }
            // update latest snapshot if this one is more recent
            if (snapshot.token > (latestSnapshot?.token ?: 0)) latestSnapshot = snapshot
            snapshot
        }
    }

    suspend fun saveSnapshot(snapshot: Snapshot) {
        val buffer = Buffer()
        val bufferStream = buffer.outputStream()
        bufferStream.write(VERSION.toInt())
        crypto.newEncryptingStream(bufferStream, crypto.getAdForVersion()).use { cryptoStream ->
            ZstdOutputStream(cryptoStream).use { zstdOutputStream ->
                snapshot.writeTo(zstdOutputStream)
            }
        }
        val sha256ByteString = buffer.sha256()
        val handle = AppBackupFileType.Snapshot(crypto.repoId, sha256ByteString.hex())
        // TODO exception handling
        backendManager.backend.save(handle).use { outputStream ->
            outputStream.sink().buffer().apply {
                writeAll(buffer)
                flush() // needs flushing
            }
        }
    }

}
