package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.transport.backup.FullBackupPlugin
import com.stevesoltys.seedvault.transport.backup.KVBackupPlugin
import java.io.IOException
import java.io.OutputStream

private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

@Suppress("BlockingMethodInNonBlockingContext")
internal class DocumentsProviderBackupPlugin(
    private val context: Context,
    private val storage: DocumentsStorage
) : BackupPlugin {

    private val packageManager: PackageManager = context.packageManager

    override val kvBackupPlugin: KVBackupPlugin by lazy {
        DocumentsProviderKVBackup(storage, context)
    }

    override val fullBackupPlugin: FullBackupPlugin by lazy {
        DocumentsProviderFullBackup(storage, context)
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice(newToken: Long): Boolean {
        // check if storage is already initialized
        if (storage.isInitialized()) return false

        // reset current storage
        storage.reset(newToken)

        // get or create root backup dir
        storage.rootBackupDir ?: throw IOException()

        // create backup folders
        val kvDir = storage.currentKvBackupDir
        val fullDir = storage.currentFullBackupDir

        // wipe existing data
        storage.getSetDir()?.findFileBlocking(context, FILE_BACKUP_METADATA)?.delete()
        kvDir?.deleteContents()
        fullDir?.deleteContents()

        return true
    }

    @Throws(IOException::class)
    override suspend fun getMetadataOutputStream(): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val metadataFile = setDir.createOrGetFile(context, FILE_BACKUP_METADATA)
        return storage.getOutputStream(metadataFile)
    }

    @Throws(IOException::class)
    override suspend fun getApkOutputStream(packageInfo: PackageInfo): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val file = setDir.createOrGetFile(context, "${packageInfo.packageName}.apk", MIME_TYPE_APK)
        return storage.getOutputStream(file)
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.getAuthority() ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}
