package com.stevesoltys.backup.transport.restore.plugins

import android.app.backup.RestoreSet
import com.stevesoltys.backup.transport.DEFAULT_RESTORE_SET_TOKEN
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.restore.FullRestorePlugin
import com.stevesoltys.backup.transport.restore.KVRestorePlugin
import com.stevesoltys.backup.transport.restore.RestorePlugin

class DocumentsProviderRestorePlugin(
        private val documentsStorage: DocumentsStorage) : RestorePlugin {

    override val kvRestorePlugin: KVRestorePlugin by lazy {
        DocumentsProviderKVRestorePlugin(documentsStorage)
    }

    override val fullRestorePlugin: FullRestorePlugin by lazy {
        DocumentsProviderFullRestorePlugin(documentsStorage)
    }

    override fun getAvailableRestoreSets(): Array<RestoreSet>? {
        return arrayOf(RestoreSet("default", "device", DEFAULT_RESTORE_SET_TOKEN))
    }

    override fun getCurrentRestoreSet(): Long {
        return DEFAULT_RESTORE_SET_TOKEN
    }

}
