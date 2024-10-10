/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
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
        get() = snapshot?.token ?: backupMetadata.time

    val size: Long = snapshot?.blobsMap?.values?.sumOf { it.uncompressedLength.toLong() }
        ?: backupMetadata.size

    val deviceName: String
        get() = backupMetadata.deviceName

    val user: String?
        get() = snapshot?.user?.takeIf { it.isNotBlank() }

    val d2dBackup: Boolean
        get() = backupMetadata.d2dBackup

    val numAppData: Int = snapshot?.appsMap?.values?.count { it.chunkIdsCount > 0 }
        ?: packageMetadataMap.values.count { packageMetadata ->
            packageMetadata.backupType != null && packageMetadata.state == APK_AND_DATA
        }

    val sizeAppData: Long = snapshot?.appsMap?.values?.sumOf { it.size }
        ?: packageMetadataMap.values.sumOf { it.size ?: 0L }

    val numApks: Int = snapshot?.appsMap?.values?.count { it.apk.splitsCount > 0 }
        ?: packageMetadataMap.values.count { it.hasApk() }

    val sizeApks: Long = size - sizeAppData

    val packageMetadataMap: PackageMetadataMap
        get() = backupMetadata.packageMetadataMap

}
