package com.stevesoltys.backup.transport.backup.plugins

import android.content.pm.PackageManager
import com.stevesoltys.backup.transport.backup.BackupPlugin
import com.stevesoltys.backup.transport.backup.FullBackupPlugin
import com.stevesoltys.backup.transport.backup.KVBackupPlugin
import java.io.IOException

class DocumentsProviderBackupPlugin(
        private val storage: DocumentsStorage,
        packageManager: PackageManager) : BackupPlugin {

    override val kvBackupPlugin: KVBackupPlugin by lazy {
        DocumentsProviderKVBackup(storage)
    }

    override val fullBackupPlugin: FullBackupPlugin by lazy {
        DocumentsProviderFullBackup(storage)
    }

    @Throws(IOException::class)
    override fun initializeDevice() {
        // get or create root backup dir
        storage.rootBackupDir ?: throw IOException()

        // create backup folders
        val kvDir = storage.defaultKvBackupDir
        val fullDir = storage.defaultFullBackupDir

        // wipe existing data
        kvDir?.deleteContents()
        fullDir?.deleteContents()
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.rootBackupDir?.uri?.authority ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}
