package com.stevesoltys.backup.transport.backup.plugins

import android.content.pm.PackageInfo
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.backup.transport.backup.DEFAULT_QUOTA_KEY_VALUE_BACKUP
import com.stevesoltys.backup.transport.backup.KVBackupPlugin
import java.io.IOException
import java.io.OutputStream

class DocumentsProviderKVBackup(private val storage: DocumentsStorage) : KVBackupPlugin {

    private var packageFile: DocumentFile? = null

    override fun getQuota(): Long = DEFAULT_QUOTA_KEY_VALUE_BACKUP

    @Throws(IOException::class)
    override fun hasDataForPackage(packageInfo: PackageInfo): Boolean {
        val packageFile = storage.currentKvBackupDir?.findFile(packageInfo.packageName)
                ?: return false
        return packageFile.listFiles().isNotEmpty()
    }

    @Throws(IOException::class)
    override fun ensureRecordStorageForPackage(packageInfo: PackageInfo) {
        // remember package file for subsequent operations
        packageFile = storage.getOrCreateKVBackupDir().createOrGetDirectory(packageInfo.packageName)
    }

    @Throws(IOException::class)
    override fun removeDataOfPackage(packageInfo: PackageInfo) {
        // we cannot use the cached this.packageFile here,
        // because this can be called before [ensureRecordStorageForPackage]
        val packageFile = storage.currentKvBackupDir?.findFile(packageInfo.packageName) ?: return
        packageFile.delete()
    }

    @Throws(IOException::class)
    override fun deleteRecord(packageInfo: PackageInfo, key: String) {
        val packageFile = this.packageFile ?: throw AssertionError()
        packageFile.assertRightFile(packageInfo)
        val keyFile = packageFile.findFile(key) ?: return
        keyFile.delete()
    }

    @Throws(IOException::class)
    override fun getOutputStreamForRecord(packageInfo: PackageInfo, key: String): OutputStream {
        val packageFile = this.packageFile ?: throw AssertionError()
        packageFile.assertRightFile(packageInfo)
        val keyFile = packageFile.createOrGetFile(key)
        return storage.getOutputStream(keyFile)
    }

}
