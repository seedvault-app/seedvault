package com.stevesoltys.backup.settings

import android.app.Application
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.R
import com.stevesoltys.backup.transport.requestBackup
import com.stevesoltys.backup.ui.RequireProvisioningViewModel

private val TAG = SettingsViewModel::class.java.simpleName

class SettingsViewModel(app: Application) : RequireProvisioningViewModel(app) {

    override val isRestoreOperation = false

    fun backupNow() {
        val nm = (app as Backup).notificationManager
        nm.onBackupUpdate(app.getString(R.string.notification_backup_starting), 0, 1, true)
        Thread { requestBackup(app) }.start()
    }

}
