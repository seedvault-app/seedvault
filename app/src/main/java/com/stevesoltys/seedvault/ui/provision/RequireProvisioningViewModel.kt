package com.stevesoltys.seedvault.ui.provision

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.seedvault.service.crypto.KeyManager
import com.stevesoltys.seedvault.service.settings.SettingsService
import com.stevesoltys.seedvault.ui.liveevent.LiveEvent
import com.stevesoltys.seedvault.ui.liveevent.MutableLiveEvent
import com.stevesoltys.seedvault.ui.storage.StorageViewModel

abstract class RequireProvisioningViewModel(
    protected val app: Application,
    protected val settingsService: SettingsService,
    protected val keyManager: KeyManager,
) : AndroidViewModel(app) {

    abstract val isRestoreOperation: Boolean

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> get() = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    internal fun validLocationIsSet() = StorageViewModel.validLocationIsSet(app, settingsService)

    internal fun recoveryCodeIsSet() = keyManager.hasBackupKey()

    open fun onStorageLocationChanged() {
        // noop can be overwritten by sub-classes
    }

}
