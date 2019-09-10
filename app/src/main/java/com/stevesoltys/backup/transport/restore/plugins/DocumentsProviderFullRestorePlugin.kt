package com.stevesoltys.backup.transport.restore.plugins

import android.content.pm.PackageInfo
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.restore.FullRestorePlugin
import java.io.IOException
import java.io.InputStream

class DocumentsProviderFullRestorePlugin(
        private val documentsStorage: DocumentsStorage) : FullRestorePlugin {

    @Throws(IOException::class)
    override fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: throw IOException()
        return backupDir.findFile(packageInfo.packageName) != null
    }

    @Throws(IOException::class)
    override fun getInputStreamForPackage(token: Long, packageInfo: PackageInfo): InputStream {
        val backupDir = documentsStorage.getFullBackupDir(token) ?: throw IOException()
        val packageFile = backupDir.findFile(packageInfo.packageName) ?: throw IOException()
        return documentsStorage.getInputStream(packageFile)
    }

}
