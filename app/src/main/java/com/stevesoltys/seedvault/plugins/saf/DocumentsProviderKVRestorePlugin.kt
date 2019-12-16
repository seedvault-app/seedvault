package com.stevesoltys.seedvault.plugins.saf

import android.content.pm.PackageInfo
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import java.io.IOException
import java.io.InputStream

internal class DocumentsProviderKVRestorePlugin(private val storage: DocumentsStorage) : KVRestorePlugin {

    private var packageDir: DocumentFile? = null

    override fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return try {
            val backupDir = storage.getKVBackupDir(token) ?: return false
            // remember package file for subsequent operations
            packageDir = backupDir.findFile(packageInfo.packageName)
            packageDir != null
        } catch (e: IOException) {
            false
        }
    }

    override fun listRecords(token: Long, packageInfo: PackageInfo): List<String> {
        val packageDir = this.packageDir ?: throw AssertionError()
        packageDir.assertRightFile(packageInfo)
        return packageDir.listFiles()
                .filter { file -> file.name != null }
                .map { file -> file.name!! }
    }

    @Throws(IOException::class)
    override fun getInputStreamForRecord(token: Long, packageInfo: PackageInfo, key: String): InputStream {
        val packageDir = this.packageDir ?: throw AssertionError()
        packageDir.assertRightFile(packageInfo)
        val keyFile = packageDir.findFile(key) ?: throw IOException()
        return storage.getInputStream(keyFile)
    }

}
