/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.annotation.UiThread
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.saf.SafHandler
import com.stevesoltys.seedvault.plugins.saf.SafStorage
import com.stevesoltys.seedvault.plugins.webdav.WebDavConfig
import com.stevesoltys.seedvault.plugins.webdav.WebDavHandler
import com.stevesoltys.seedvault.plugins.webdav.WebDavProperties
import com.stevesoltys.seedvault.plugins.webdav.WebDavStoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal abstract class StorageViewModel(
    private val app: Application,
    protected val safHandler: SafHandler,
    protected val webdavHandler: WebDavHandler,
    protected val settingsManager: SettingsManager,
    protected val storagePluginManager: StoragePluginManager,
) : AndroidViewModel(app), RemovableStorageListener {

    private val mStorageOptions = MutableLiveData<List<StorageOption>>()
    internal val storageOptions: LiveData<List<StorageOption>> get() = mStorageOptions

    private val mLocationSet = MutableLiveEvent<Boolean>()
    internal val locationSet: LiveEvent<Boolean> get() = mLocationSet

    protected val mLocationChecked = MutableLiveEvent<LocationResult>()
    internal val locationChecked: LiveEvent<LocationResult> get() = mLocationChecked

    private val storageOptionFetcher by lazy { StorageOptionFetcher(app, isRestoreOperation) }
    private var safOption: SafOption? = null

    internal var isSetupWizard: Boolean = false
    internal val hasStorageSet: Boolean
        get() = storagePluginManager.storageProperties != null
    abstract val isRestoreOperation: Boolean

    internal fun loadStorageRoots() {
        if (storageOptionFetcher.getRemovableStorageListener() == null) {
            storageOptionFetcher.setRemovableStorageListener(this)
        }
        Thread {
            mStorageOptions.postValue(storageOptionFetcher.getStorageOptions())
        }.start()
    }

    override fun onStorageChanged() = loadStorageRoots()

    /**
     * Remembers that the user chose SAF.
     * Usually followed by a call of [onUriPermissionResultReceived].
     */
    fun onSafOptionChosen(option: SafOption) {
        safOption = option
    }

    internal fun onUriPermissionResultReceived(uri: Uri?) {
        if (uri == null) {
            val msg = app.getString(R.string.storage_check_fragment_permission_error)
            mLocationChecked.setEvent(LocationResult(msg))
            return
        }
        require(safOption?.uri == uri) { "URIs differ: ${safOption?.uri} != $uri" }

        val root = safOption ?: throw IllegalStateException("no storage root")
        val safStorage = safHandler.onConfigReceived(uri, root)

        // inform UI that a location has been successfully selected
        mLocationSet.setEvent(true)

        onSafUriSet(safStorage)
    }

    abstract fun onSafUriSet(safStorage: SafStorage)
    abstract fun onWebDavConfigSet(properties: WebDavProperties, plugin: WebDavStoragePlugin)

    override fun onCleared() {
        storageOptionFetcher.setRemovableStorageListener(null)
        super.onCleared()
    }
    val webdavConfigState get() = webdavHandler.configState

    fun onWebDavConfigReceived(url: String, user: String, pass: String) {
        val config = WebDavConfig(url = url, username = user, password = pass)
        viewModelScope.launch(Dispatchers.IO) {
            webdavHandler.onConfigReceived(config)
        }
    }

    fun resetWebDavConfig() = webdavHandler.resetConfigState()

    @UiThread
    fun onWebDavConfigSuccess(properties: WebDavProperties, plugin: WebDavStoragePlugin) {
        mLocationSet.setEvent(true)
        onWebDavConfigSet(properties, plugin)
    }

}

class LocationResult(val errorMsg: String? = null)
