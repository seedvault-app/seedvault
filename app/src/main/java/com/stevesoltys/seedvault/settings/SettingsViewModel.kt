package com.stevesoltys.seedvault.settings

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.transport.requestBackup
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel

class SettingsViewModel(
        app: Application,
        settingsManager: SettingsManager,
        keyManager: KeyManager,
        private val metadataManager: MetadataManager
) : RequireProvisioningViewModel(app, settingsManager, keyManager) {

    override val isRestoreOperation = false

    private val _lastBackupTime = MutableLiveData<Long>()
    internal val lastBackupTime: LiveData<Long> = _lastBackupTime

    internal fun updateLastBackupTime() {
        Thread { _lastBackupTime.postValue(metadataManager.getLastBackupTime()) }.start()
    }

    internal fun backupNow() {
        Thread { requestBackup(app) }.start()
    }

}
