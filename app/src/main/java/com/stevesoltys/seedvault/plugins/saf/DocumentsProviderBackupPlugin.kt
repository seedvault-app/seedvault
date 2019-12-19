package com.stevesoltys.seedvault.plugins.saf

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.transport.backup.FullBackupPlugin
import com.stevesoltys.seedvault.transport.backup.KVBackupPlugin
import java.io.IOException
import java.io.OutputStream

private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

internal class DocumentsProviderBackupPlugin(
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
        val kvDir = storage.currentKvBackupDir
        val fullDir = storage.currentFullBackupDir

        // wipe existing data
        storage.getSetDir()?.findFile(FILE_BACKUP_METADATA)?.delete()
        kvDir?.deleteContents()
        fullDir?.deleteContents()
    }

    @Throws(IOException::class)
    override fun getMetadataOutputStream(): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val metadataFile = setDir.createOrGetFile(FILE_BACKUP_METADATA)
        return storage.getOutputStream(metadataFile)
    }

    @Throws(IOException::class)
    override fun getApkOutputStream(packageInfo: PackageInfo): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val file = setDir.createOrGetFile("${packageInfo.packageName}.apk", MIME_TYPE_APK)
        return storage.getOutputStream(file)
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.getAuthority() ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}
