package com.stevesoltys.seedvault.settings

import android.app.Application
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel

class SettingsViewModel(app: Application) : RequireProvisioningViewModel(app) {

    override val isRestoreOperation = false

    fun backupNow() {
        Thread { requestBackup(app) }.start()
    }

}
