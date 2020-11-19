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
    private val storage: DocumentsStorage,
    override val kvBackupPlugin: KVBackupPlugin,
    override val fullBackupPlugin: FullBackupPlugin
) : BackupPlugin {

    private val packageManager: PackageManager = context.packageManager

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        // reset current storage
        storage.reset(token)

        // get or create root backup dir
        storage.rootBackupDir ?: throw IOException()
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // wipe existing data
        storage.getSetDir()?.deleteContents(context)

        // reset storage without new token, so folders get recreated
        // otherwise stale DocumentFiles will hang around
        storage.reset(null)

        // create backup folders
        storage.currentKvBackupDir ?: throw IOException()
        storage.currentFullBackupDir ?: throw IOException()
    }

    @Throws(IOException::class)
    override suspend fun deleteAllBackups() {
        storage.rootBackupDir?.deleteContents(context)
    }

    @Throws(IOException::class)
    override suspend fun getMetadataOutputStream(): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val metadataFile = setDir.createOrGetFile(context, FILE_BACKUP_METADATA)
        return storage.getOutputStream(metadataFile)
    }

    @Throws(IOException::class)
    override suspend fun getApkOutputStream(
        packageInfo: PackageInfo,
        suffix: String
    ): OutputStream {
        val setDir = storage.getSetDir() ?: throw IOException()
        val name = "${packageInfo.packageName}$suffix.apk"
        val file = setDir.createOrGetFile(context, name, MIME_TYPE_APK)
        return storage.getOutputStream(file)
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.getAuthority() ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}
