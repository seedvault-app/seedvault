package com.stevesoltys.backup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.ui.storage.StorageViewModel

abstract class RequireProvisioningViewModel(protected val app: Application) : AndroidViewModel(app) {

    abstract val isRestoreOperation: Boolean

    private val mChooseBackupLocation = MutableLiveEvent<Boolean>()
    internal val chooseBackupLocation: LiveEvent<Boolean> get() = mChooseBackupLocation
    internal fun chooseBackupLocation() = mChooseBackupLocation.setEvent(true)

    internal fun validLocationIsSet() = StorageViewModel.validLocationIsSet(app)

    internal fun recoveryCodeIsSet() = Backup.keyManager.hasBackupKey()

}
