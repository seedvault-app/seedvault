/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import android.content.Context
import com.stevesoltys.seedvault.plugins.saf.SafStorage
import org.calyxos.backup.storage.plugin.saf.SafStoragePlugin
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafConfig

internal class SeedvaultSafStoragePlugin(
    appContext: Context,
    safStorage: SafStorage,
    root: String = DIRECTORY_ROOT,
) : SafStoragePlugin(appContext) {
    private val safConfig = SafConfig(
        config = safStorage.config,
        name = safStorage.name,
        isUsb = safStorage.isUsb,
        requiresNetwork = safStorage.requiresNetwork,
        rootId = safStorage.rootId,
    )
    override val delegate: SafBackend = SafBackend(appContext, safConfig, root)
}
