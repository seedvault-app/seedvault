package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.transport.backup.DEFAULT_QUOTA_KEY_VALUE_BACKUP
import com.stevesoltys.seedvault.transport.backup.KVBackupPlugin
import java.io.IOException
import java.io.OutputStream

const val MAX_KEY_LENGTH = 255
const val MAX_KEY_LENGTH_NEXTCLOUD = 225

@Suppress("BlockingMethodInNonBlockingContext")
internal class DocumentsProviderKVBackup(
    private val context: Context,
    private val storage: DocumentsStorage
) : KVBackupPlugin {

    private var packageFile: DocumentFile? = null
    private var packageChildren: List<DocumentFile>? = null

    override fun getQuota(): Long = DEFAULT_QUOTA_KEY_VALUE_BACKUP

    @Throws(IOException::class)
    override suspend fun hasDataForPackage(packageInfo: PackageInfo): Boolean {
        // get the folder for the package (or create it) and all files in it
        val dir =
            storage.getOrCreateKVBackupDir().createOrGetDirectory(context, packageInfo.packageName)
        val children = dir.listFilesBlocking(context)
        // cache package file for subsequent operations
        packageFile = dir
        // also cache children as doing this for every record is super slow
        packageChildren = children
        return children.isNotEmpty()
    }

    @Throws(IOException::class)
    override suspend fun getOutputStreamForRecord(
        packageInfo: PackageInfo,
        key: String
    ): OutputStream {
        // check maximum key lengths
        check(key.length <= MAX_KEY_LENGTH) {
            "Key $key for ${packageInfo.packageName} is too long: ${key.length} chars."
        }
        if (key.length > MAX_KEY_LENGTH_NEXTCLOUD) {
            Log.e(
                DocumentsProviderKVBackup::class.java.simpleName,
                "Key $key for ${packageInfo.packageName} is too long: ${key.length} chars."
            )
        }
        // get dir and children from cache
        val packageFile = this.packageFile
            ?: throw AssertionError("No cached packageFile for ${packageInfo.packageName}")
        packageFile.assertRightFile(packageInfo)
        val children = packageChildren
            ?: throw AssertionError("No cached children for ${packageInfo.packageName}")

        // get file for key from cache,
        val keyFile = children.find { it.name == key } // try cache first
            ?: packageFile.createFile(MIME_TYPE, key) // assume it doesn't exist, create it
            ?: packageFile.createOrGetFile(context, key) // cache was stale, so try to find it
        check(keyFile.name == key) { "Key file named ${keyFile.name}, but should be $key" }
        return storage.getOutputStream(keyFile)
    }

    @Throws(IOException::class)
    override suspend fun deleteRecord(packageInfo: PackageInfo, key: String) {
        val packageFile = this.packageFile
            ?: throw AssertionError("No cached packageFile for ${packageInfo.packageName}")
        packageFile.assertRightFile(packageInfo)

        val children = packageChildren
            ?: throw AssertionError("No cached children for ${packageInfo.packageName}")

        // try to find file for given key and delete it if found
        val keyFile = children.find { it.name == key } // try to find in cache
            ?: packageFile.findFileBlocking(context, key) // fall-back to provider
            ?: return // not found, nothing left to do
        keyFile.delete()

        // we don't update the children cache as deleted records
        // are not expected to get re-added in the same backup pass
    }

    @Throws(IOException::class)
    override suspend fun removeDataOfPackage(packageInfo: PackageInfo) {
        val packageFile = this.packageFile
            ?: throw AssertionError("No cached packageFile for ${packageInfo.packageName}")
        packageFile.assertRightFile(packageInfo)
        // We are not using the cached children here in case they are stale.
        // This operation isn't frequent, so we don't need to heavily optimize it.
        packageFile.deleteContents(context)
        // clear children cache
        packageChildren = ArrayList()
    }

    override fun packageFinished(packageInfo: PackageInfo) {
        packageFile = null
        packageChildren = null
    }

}
