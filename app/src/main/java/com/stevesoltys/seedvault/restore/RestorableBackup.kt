package com.stevesoltys.seedvault.restore

import android.app.backup.RestoreSet
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap

data class RestorableBackup(
    private val restoreSet: RestoreSet,
    private val backupMetadata: BackupMetadata
) {

    val name: String
        get() = restoreSet.name

    val token: Long
        get() = restoreSet.token

    val time: Long
        get() = backupMetadata.time

    val deviceName: String
        get() = backupMetadata.deviceName

    val packageMetadataMap: PackageMetadataMap
        get() = backupMetadata.packageMetadataMap

}
