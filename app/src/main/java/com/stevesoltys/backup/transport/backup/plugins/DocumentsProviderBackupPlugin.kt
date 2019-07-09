package com.stevesoltys.backup.transport.backup.plugins

import android.content.pm.PackageManager
import com.stevesoltys.backup.transport.backup.BackupPlugin
import com.stevesoltys.backup.transport.backup.FullBackupPlugin
import com.stevesoltys.backup.transport.backup.KVBackupPlugin
import java.io.IOException

private const val NO_MEDIA = ".nomedia"

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
        val rootDir = storage.rootBackupDir ?: throw IOException()

        // create .nomedia file to prevent Android's MediaScanner from trying to index the backup
        rootDir.createOrGetFile(NO_MEDIA)

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
