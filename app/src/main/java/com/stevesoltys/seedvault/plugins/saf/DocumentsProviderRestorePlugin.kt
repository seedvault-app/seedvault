package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

private val TAG = DocumentsProviderRestorePlugin::class.java.simpleName

@WorkerThread
@Suppress("BlockingMethodInNonBlockingContext") // all methods do I/O
internal class DocumentsProviderRestorePlugin(
    private val context: Context,
    private val storage: DocumentsStorage,
    override val kvRestorePlugin: KVRestorePlugin,
    override val fullRestorePlugin: FullRestorePlugin
) : RestorePlugin {

    @Throws(IOException::class)
    override suspend fun hasBackup(uri: Uri): Boolean {
        val parent = DocumentFile.fromTreeUri(context, uri) ?: throw AssertionError()
        val rootDir = parent.findFileBlocking(context, DIRECTORY_ROOT) ?: return false
        val backupSets = getBackups(context, rootDir)
        return backupSets.isNotEmpty()
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedBackupMetadata>? {
        val rootDir = storage.rootBackupDir ?: return null
        val backupSets = getBackups(context, rootDir)
        val iterator = backupSets.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null // end sequence
            val backupSet = iterator.next()
            try {
                val stream = storage.getInputStream(backupSet.metadataFile)
                EncryptedBackupMetadata(backupSet.token, stream)
            } catch (e: IOException) {
                Log.e(TAG, "Error getting InputStream for backup metadata.", e)
                EncryptedBackupMetadata(backupSet.token)
            }
        }
    }

    @Throws(IOException::class)
    override suspend fun getApkInputStream(
        token: Long,
        packageName: String,
        suffix: String
    ): InputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, "$packageName$suffix.apk")
            ?: throw FileNotFoundException()
        return storage.getInputStream(file)
    }

}
