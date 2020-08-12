package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import java.io.IOException
import java.io.InputStream

@Suppress("BlockingMethodInNonBlockingContext")
internal class DocumentsProviderFullRestorePlugin(
    private val context: Context,
    private val documentsStorage: DocumentsStorage
) : FullRestorePlugin {

    @Throws(IOException::class)
    override suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: return false
        return backupDir.findFileBlocking(context, packageInfo.packageName) != null
    }

    @Throws(IOException::class)
    override suspend fun getInputStreamForPackage(
        token: Long,
        packageInfo: PackageInfo
    ): InputStream {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: throw IOException()
        val packageFile =
            backupDir.findFileBlocking(context, packageInfo.packageName) ?: throw IOException()
        return documentsStorage.getInputStream(packageFile)
    }

}
