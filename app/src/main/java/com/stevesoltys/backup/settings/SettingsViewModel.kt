package com.stevesoltys.backup.settings

import android.app.Application
import com.stevesoltys.backup.transport.requestBackup
import com.stevesoltys.backup.ui.BackupViewModel

class SettingsViewModel(app: Application) : BackupViewModel(app) {

    fun backupNow() = Thread { requestBackup(app) }.start()

}
