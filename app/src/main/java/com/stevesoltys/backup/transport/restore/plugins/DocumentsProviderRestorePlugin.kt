package com.stevesoltys.backup.transport.restore.plugins

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.backup.metadata.EncryptedBackupMetadata
import com.stevesoltys.backup.transport.DEFAULT_RESTORE_SET_TOKEN
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.backup.plugins.FILE_BACKUP_METADATA
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
        val files = ArrayList<DocumentFile>()
        for (file in rootDir.listFiles()) {
            file.isDirectory || continue
            val set = file.findFile(DEFAULT_RESTORE_SET_TOKEN.toString()) ?: continue
            val metadata = set.findFile(FILE_BACKUP_METADATA) ?: continue
            files.add(metadata)
        }
        val iterator = files.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null  // end sequence
            val metadata = iterator.next()
            val token = metadata.parentFile!!.name!!.toLong()
            try {
                val stream = storage.getInputStream(metadata)
                EncryptedBackupMetadata(token, stream)
            } catch (e: IOException) {
                Log.e(TAG, "Error getting InputStream for backup metadata.", e)
                EncryptedBackupMetadata(token, true)
            }
        }
    }

    override fun getCurrentRestoreSet(): Long {
        return DEFAULT_RESTORE_SET_TOKEN
    }

}
