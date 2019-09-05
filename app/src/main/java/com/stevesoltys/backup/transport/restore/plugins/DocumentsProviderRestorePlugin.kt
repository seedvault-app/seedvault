package com.stevesoltys.backup.transport.restore.plugins

import android.app.backup.RestoreSet
import com.stevesoltys.backup.transport.DEFAULT_RESTORE_SET_TOKEN
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.restore.FullRestorePlugin
import com.stevesoltys.backup.transport.restore.KVRestorePlugin
import com.stevesoltys.backup.transport.restore.RestorePlugin

class DocumentsProviderRestorePlugin(private val storage: DocumentsStorage) : RestorePlugin {

    override val kvRestorePlugin: KVRestorePlugin by lazy {
        DocumentsProviderKVRestorePlugin(storage)
    }

    override val fullRestorePlugin: FullRestorePlugin by lazy {
        DocumentsProviderFullRestorePlugin(storage)
    }

    override fun getAvailableRestoreSets(): Array<RestoreSet>? {
        val rootDir = storage.rootBackupDir ?: return null
        val restoreSets = ArrayList<RestoreSet>()
        for (file in rootDir.listFiles()) {
            if (file.isDirectory && file.findFile(DEFAULT_RESTORE_SET_TOKEN.toString()) != null) {
                // TODO include time of last backup
                file.name?.let { restoreSets.add(RestoreSet(it, it, DEFAULT_RESTORE_SET_TOKEN)) }
            }
        }
        return restoreSets.toTypedArray()
    }

    override fun getCurrentRestoreSet(): Long {
        return DEFAULT_RESTORE_SET_TOKEN
    }

}
