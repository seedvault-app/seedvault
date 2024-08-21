/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.NO_DATA_END_SENTINEL
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SYSTEM
import com.stevesoltys.seedvault.ui.systemData
import com.stevesoltys.seedvault.worker.FILE_BACKUP_ICONS
import com.stevesoltys.seedvault.worker.IconManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

internal class SelectedAppsState(
    val apps: List<SelectableAppItem>,
    val allSelected: Boolean,
    val iconsLoaded: Boolean,
)

private val TAG = AppSelectionManager::class.simpleName

internal class AppSelectionManager(
    private val context: Context,
    private val pluginManager: StoragePluginManager,
    private val iconManager: IconManager,
    private val coroutineScope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val initialState = SelectedAppsState(
        emptyList(),
        allSelected = true,
        iconsLoaded = false,
    )
    private val selectedApps = MutableStateFlow(initialState)
    val selectedAppsFlow = selectedApps.asStateFlow()
    val selectedAppsLiveData: LiveData<SelectedAppsState> = selectedApps.asLiveData()

    fun onRestoreSetChosen(restorableBackup: RestorableBackup, isSetupWizard: Boolean) {
        // filter and sort app items for display
        val items = restorableBackup.packageMetadataMap.mapNotNull { (packageName, metadata) ->
            if (metadata.time == 0L && !metadata.hasApk()) null
            else if (metadata.isInternalSystem) null
            else SelectableAppItem(packageName, metadata, true)
        }.sortedBy {
            it.name.lowercase(Locale.getDefault())
        }.toMutableList()
        val systemDataItems = systemData.mapNotNull { (packageName, data) ->
            val metadata = restorableBackup.packageMetadataMap[packageName]
                ?: return@mapNotNull null
            if (metadata.time == 0L && !metadata.hasApk()) return@mapNotNull null
            val name = context.getString(data.nameRes)
            SelectableAppItem(packageName, metadata.copy(name = name), true)
        }
        val systemItem = SelectableAppItem(
            packageName = PACKAGE_NAME_SYSTEM,
            metadata = PackageMetadata(
                time = restorableBackup.packageMetadataMap.values.maxOf {
                    if (it.system) it.time else -1
                },
                size = restorableBackup.packageMetadataMap.values.sumOf {
                    if (it.system) it.size ?: 0L else 0L
                },
                system = true,
                name = context.getString(R.string.backup_system_apps),
            ),
            selected = isSetupWizard,
        )
        items.add(0, systemItem)
        items.addAll(0, systemDataItems)
        selectedApps.value =
            SelectedAppsState(apps = items, allSelected = isSetupWizard, iconsLoaded = false)
        // download icons
        coroutineScope.launch(workDispatcher) {
            val plugin = pluginManager.appPlugin
            val token = restorableBackup.token
            val packagesWithIcons = try {
                plugin.getInputStream(token, FILE_BACKUP_ICONS).use {
                    iconManager.downloadIcons(restorableBackup.version, token, it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading icons:", e)
                emptySet()
            } + systemData.keys + setOf(PACKAGE_NAME_SYSTEM) // special apps have built-in icons
            // update state, so it knows that icons have loaded
            val updatedItems = items.map { item ->
                item.copy(hasIcon = item.packageName in packagesWithIcons)
            }
            selectedApps.value =
                SelectedAppsState(updatedItems, allSelected = isSetupWizard, iconsLoaded = true)
        }
    }

    fun onCheckAllAppsClicked() {
        val apps = selectedApps.value.apps
        val allSelected = apps.all { it.selected }
        if (allSelected) {
            // unselect all
            val newApps = apps.map { if (it.selected) it.copy(selected = false) else it }
            selectedApps.value = SelectedAppsState(newApps, false, iconsLoaded = true)
        } else {
            // select all
            val newApps = apps.map { if (!it.selected) it.copy(selected = true) else it }
            selectedApps.value = SelectedAppsState(newApps, true, iconsLoaded = true)
        }
    }

    fun onAppSelected(item: SelectableAppItem) {
        val apps = selectedApps.value.apps.toMutableList()
        val iterator = apps.listIterator()
        var allSelected = true
        while (iterator.hasNext()) {
            val app = iterator.next()
            if (app.packageName == item.packageName) {
                iterator.set(item.copy(selected = !item.selected))
                allSelected = allSelected && !item.selected
            } else {
                allSelected = allSelected && app.selected
            }
        }
        selectedApps.value = SelectedAppsState(apps, allSelected, iconsLoaded = true)
    }

    fun onAppSelectionFinished(backup: RestorableBackup): RestorableBackup {
        // map packages names to selection state
        val apps = selectedApps.value.apps.associate {
            Pair(it.packageName, it.selected)
        }
        // filter out unselected packages
        // Attention: This code is complicated and hard to test, proceed with plenty of care!
        val restoreSystemApps = apps[PACKAGE_NAME_SYSTEM] != false
        val packages = backup.packageMetadataMap.filter { (packageName, metadata) ->
            val isSelected = apps[packageName]
            @Suppress("IfThenToElvis") // the code is more readable like this
            if (isSelected == null) { // was not in list
                if (packageName == MAGIC_PACKAGE_MANAGER) true // @pm@ is essential for restore
                else if (packageName == NO_DATA_END_SENTINEL) false // @end@ is not real
                // internal system apps were not in the list and are controlled by meta item,
                // so allow them only if meta item was selected
                else if (metadata.isInternalSystem) restoreSystemApps
                else true // non-system packages that weren't found, won't get filtered
            } else { // was in list and either selected or not
                isSelected
            }
        } as PackageMetadataMap
        // replace original chosen backup with unselected packages removed
        return backup.copy(
            backupMetadata = backup.backupMetadata.copy(packageMetadataMap = packages),
        )
    }

}
