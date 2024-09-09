/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.proto.Snapshot

sealed class RestorableBackupResult {
    data class ErrorResult(val e: Exception?) : RestorableBackupResult()
    data class SuccessResult(val backups: List<RestorableBackup>) : RestorableBackupResult()
}

data class RestorableBackup(
    val backupMetadata: BackupMetadata,
    val repoId: String? = null,
    val snapshot: Snapshot? = null,
) {

    constructor(repoId: String, snapshot: Snapshot) : this(
        backupMetadata = BackupMetadata.fromSnapshot(snapshot),
        repoId = repoId,
        snapshot = snapshot,
    )

    val name: String
        get() = backupMetadata.deviceName

    val version: Byte
        get() = backupMetadata.version

    val token: Long
        get() = backupMetadata.token

    val salt: String
        get() = backupMetadata.salt

    val time: Long
        get() = backupMetadata.time

    val size: Long
        get() = snapshot?.blobsMap?.values?.sumOf { it.uncompressedLength.toLong() }
            ?: backupMetadata.size

    val deviceName: String
        get() = backupMetadata.deviceName

    val d2dBackup: Boolean
        get() = backupMetadata.d2dBackup

    val packageMetadataMap: PackageMetadataMap
        get() = backupMetadata.packageMetadataMap

}
