/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.proto.Snapshot.Blob

/**
 * Essential metadata returned when storing backup data.
 *
 * @param chunkIds an ordered(!) list of the chunk IDs required to re-assemble the backup data.
 * @param blobMap a mapping from chunk ID to [Blob] on the backend.
 * Needed for fetching blobs from the backend for re-assembly.
 */
data class BackupData(
    val chunkIds: List<String>,
    val blobMap: Map<String, Blob>,
) {
    /**
     * The uncompressed plaintext size of all blobs.
     */
    val size get() = blobMap.values.sumOf { it.uncompressedLength }.toLong()
}
