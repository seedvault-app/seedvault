package com.stevesoltys.backup.settings

import android.app.Application
import com.stevesoltys.backup.transport.requestBackup
import com.stevesoltys.backup.ui.RequireProvisioningViewModel

class SettingsViewModel(app: Application) : RequireProvisioningViewModel(app) {

    override val isRestoreOperation = false

    fun backupNow() {
        Thread { requestBackup(app) }.start()
    }

}
