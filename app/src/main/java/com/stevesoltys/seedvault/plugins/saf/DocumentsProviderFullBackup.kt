package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import com.stevesoltys.seedvault.transport.backup.DEFAULT_QUOTA_FULL_BACKUP
import com.stevesoltys.seedvault.transport.backup.FullBackupPlugin
import java.io.IOException
import java.io.OutputStream

private val TAG = DocumentsProviderFullBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class DocumentsProviderFullBackup(
    private val context: Context,
    private val storage: DocumentsStorage
) : FullBackupPlugin {

    override fun getQuota() = DEFAULT_QUOTA_FULL_BACKUP

    @Throws(IOException::class)
    override suspend fun getOutputStream(targetPackage: PackageInfo): OutputStream {
        val file = storage.currentFullBackupDir?.createOrGetFile(context, targetPackage.packageName)
            ?: throw IOException()
        return storage.getOutputStream(file)
    }

    @Throws(IOException::class)
    override suspend fun removeDataOfPackage(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        Log.i(TAG, "Deleting $packageName...")
        val file = storage.currentFullBackupDir?.findFileBlocking(context, packageName)
            ?: return
        if (!file.delete()) throw IOException("Failed to delete $packageName")
    }

}
