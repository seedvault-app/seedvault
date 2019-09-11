package com.stevesoltys.backup.transport.restore.plugins

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.backup.metadata.EncryptedBackupMetadata
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.backup.plugins.FILE_BACKUP_METADATA
import com.stevesoltys.backup.transport.backup.plugins.FILE_NO_MEDIA
import com.stevesoltys.backup.transport.restore.FullRestorePlugin
import com.stevesoltys.backup.transport.restore.KVRestorePlugin
import com.stevesoltys.backup.transport.restore.RestorePlugin
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
        val files = ArrayList<Pair<Long, DocumentFile>>()
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
                files.add(Pair(token, metadata))
            }
        }
        val iterator = files.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null  // end sequence
            val pair = iterator.next()
            val token = pair.first
            val metadata = pair.second
            try {
                val stream = storage.getInputStream(metadata)
                EncryptedBackupMetadata(token, stream)
            } catch (e: IOException) {
                Log.e(TAG, "Error getting InputStream for backup metadata.", e)
                EncryptedBackupMetadata(token)
            }
        }
    }

}
