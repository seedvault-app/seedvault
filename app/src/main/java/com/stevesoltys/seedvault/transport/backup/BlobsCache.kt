/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.transport.SnapshotManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder

internal class BlobsCache(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
) {

    private val log = KotlinLogging.logger {}
    private val blobMap = mutableMapOf<String, Blob>()

    /**
     * This must be called before saving files to the backend to avoid uploading duplicate blobs.
     */
    suspend fun populateCache() {
        log.info { "Getting all blobs from backend..." }
        blobMap.clear()
        val blobs = mutableSetOf<String>()
        backendManager.backend.list(
            topLevelFolder = TopLevelFolder(crypto.repoId),
            AppBackupFileType.Blob::class,
        ) { fileInfo ->
            fileInfo.fileHandle as AppBackupFileType.Blob
            // TODO we could save size info here and later check it is as expected
            blobs.add(fileInfo.fileHandle.name)
        }
        snapshotManager.loadSnapshots { snapshot ->
            snapshot.blobsMap.forEach { (chunkId, blob) ->
                // check if referenced blob still exists on backend
                if (blobs.contains(blob.id.hexFromProto())) {
                    // only add blob to our mapping, if it still exists
                    blobMap.putIfAbsent(chunkId, blob)?.let { previous ->
                        if (previous.id != blob.id) log.warn {
                            "Chunk ID ${chunkId.substring(0..5)} had more than one blob"
                        }
                    }
                } else log.warn {
                    "Blob ${blob.id.hexFromProto()} referenced in snapshot ${snapshot.token}"
                }
            }
        }
    }

    fun getBlob(hash: String): Blob? = blobMap[hash]

    fun saveNewBlob(chunkId: String, blob: Blob) {
        blobMap[chunkId] = blob
        // TODO persist this blob locally in case backup gets interrupted
    }

    fun clear() {
        log.info { "Clearing cache..." }
        blobMap.clear()
    }

}
