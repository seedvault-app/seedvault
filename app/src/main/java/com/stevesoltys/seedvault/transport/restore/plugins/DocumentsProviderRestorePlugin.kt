package com.stevesoltys.seedvault.transport.restore.plugins

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import com.stevesoltys.seedvault.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.seedvault.transport.backup.plugins.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.transport.backup.plugins.FILE_NO_MEDIA
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import java.io.IOException

private val TAG = DocumentsProviderRestorePlugin::class.java.simpleName

class DocumentsProviderRestorePlugin(private val storage: DocumentsStorage) : RestorePlugin {

    override val kvRestorePlugin: KVRestorePlugin by lazy {
        DocumentsProviderKVRestorePlugin(storage)
    }

    override val fullRestorePlugin: FullRestorePlugin by lazy {
        DocumentsProviderFullRestorePlugin(storage)
    }

    override fun getAvailableBackups(): Sequence<EncryptedBackupMetadata>? {
        val rootDir = storage.rootBackupDir ?: return null
        val backupSets = getBackups(rootDir)
        val iterator = backupSets.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null  // end sequence
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

    companion object {
        fun getBackups(rootDir: DocumentFile): List<BackupSet> {
            val backupSets = ArrayList<BackupSet>()
            for (set in rootDir.listFiles()) {
                if (!set.isDirectory || set.name == null) {
                    if (set.name != FILE_NO_MEDIA) {
                        Log.w(TAG, "Found invalid backup set folder: ${set.name}")
                    }
                    continue
                }
                val token = try {
                    set.name!!.toLong()
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Found invalid backup set folder: ${set.name}", e)
                    continue
                }
                val metadata = set.findFile(FILE_BACKUP_METADATA)
                if (metadata == null) {
                    Log.w(TAG, "Missing metadata file in backup set folder: ${set.name}")
                } else {
                    backupSets.add(BackupSet(token, metadata))
                }
            }
            return backupSets
        }
    }

}

class BackupSet(val token: Long, val metadataFile: DocumentFile)
