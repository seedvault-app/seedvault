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

    private val tokenRegex = Regex("([0-9]{13})") // good until the year 2286
    private val chunkFolderRegex = Regex("[a-f0-9]{2}")

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

    private suspend fun getBackups(context: Context, rootDir: DocumentFile): List<BackupSet> {
        val backupSets = ArrayList<BackupSet>()
        val files = try {
            // block until the DocumentsProvider has results
            rootDir.listFilesBlocking(context)
        } catch (e: IOException) {
            Log.e(TAG, "Error loading backups from storage", e)
            return backupSets
        }
        for (set in files) {
            // retrieve name only once as this causes a DB query
            val name = set.name

            // get current token from set or continue to next file/set
            val token = set.getTokenOrNull(name) ?: continue

            // block until children of set are available
            val metadata = try {
                set.findFileBlocking(context, FILE_BACKUP_METADATA)
            } catch (e: IOException) {
                Log.e(TAG, "Error reading metadata file in backup set folder: $name", e)
                null
            }
            if (metadata == null) {
                Log.w(TAG, "Missing metadata file in backup set folder: $name")
            } else {
                backupSets.add(BackupSet(token, metadata))
            }
        }
        return backupSets
    }

    private fun DocumentFile.getTokenOrNull(name: String?): Long? {
        val looksLikeToken = name != null && tokenRegex.matches(name)
        // check for isDirectory only if we already have a valid token (causes DB query)
        if (!looksLikeToken || !isDirectory) {
            // only log unexpected output
            if (name != null && isUnexpectedFile(name)) {
                Log.w(TAG, "Found invalid backup set folder: $name")
            }
            return null
        }
        return try {
            name?.toLong()
        } catch (e: NumberFormatException) {
            throw AssertionError(e)
        }
    }

    private fun isUnexpectedFile(name: String): Boolean {
        return name != FILE_NO_MEDIA &&
            !chunkFolderRegex.matches(name) &&
            !name.endsWith(".SeedSnap")
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

class BackupSet(val token: Long, val metadataFile: DocumentFile)
