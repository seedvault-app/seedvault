package com.stevesoltys.seedvault.plugins.saf

import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import java.io.IOException
import java.io.InputStream

internal class DocumentsProviderFullRestorePlugin(
        private val documentsStorage: DocumentsStorage) : FullRestorePlugin {

    @Throws(IOException::class)
    override fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: return false
        return backupDir.findFile(packageInfo.packageName) != null
    }

    @Throws(IOException::class)
    override fun getInputStreamForPackage(token: Long, packageInfo: PackageInfo): InputStream {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: throw IOException()
        val packageFile = backupDir.findFile(packageInfo.packageName) ?: throw IOException()
        return documentsStorage.getInputStream(packageFile)
    }

}
