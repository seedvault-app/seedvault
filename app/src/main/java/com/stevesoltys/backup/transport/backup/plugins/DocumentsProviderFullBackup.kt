package com.stevesoltys.backup.transport.backup.plugins

import android.content.pm.PackageInfo
import android.util.Log
import com.stevesoltys.backup.transport.backup.DEFAULT_QUOTA_FULL_BACKUP
import com.stevesoltys.backup.transport.backup.FullBackupPlugin
import java.io.IOException
import java.io.OutputStream

private val TAG = DocumentsProviderFullBackup::class.java.simpleName

class DocumentsProviderFullBackup(
        private val storage: DocumentsStorage) : FullBackupPlugin {

    override fun getQuota() = DEFAULT_QUOTA_FULL_BACKUP

    @Throws(IOException::class)
    override fun getOutputStream(targetPackage: PackageInfo): OutputStream {
        // TODO test file-size after overwriting bigger file
        val file = storage.defaultFullBackupDir?.createOrGetFile(targetPackage.packageName)
                ?: throw IOException()
        return storage.getOutputStream(file)
    }

    @Throws(IOException::class)
    override fun removeDataOfPackage(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        Log.i(TAG, "Deleting $packageName...")
        val file = storage.defaultFullBackupDir?.findFile(packageName) ?: return
        if (!file.delete()) throw IOException("Failed to delete $packageName")
    }

}
