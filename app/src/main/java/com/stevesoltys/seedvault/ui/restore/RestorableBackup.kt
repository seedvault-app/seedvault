package com.stevesoltys.seedvault.ui.restore

import com.stevesoltys.seedvault.service.metadata.BackupMetadata
import com.stevesoltys.seedvault.service.metadata.PackageMetadataMap

data class RestorableBackup(val backupMetadata: BackupMetadata) {

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

    val deviceName: String
        get() = backupMetadata.deviceName

    val d2dBackup: Boolean
        get() = backupMetadata.d2dBackup

    val packageMetadataMap: PackageMetadataMap
        get() = backupMetadata.packageMetadataMap

}
