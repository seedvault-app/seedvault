/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import com.stevesoltys.seedvault.storage.SeedvaultSafStoragePlugin
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.saf.SafBackend

class SafFactory(
    private val context: Context,
) {

    internal fun createBackend(safStorage: SafStorage): Backend {
        return SafBackend(context, safStorage.toSafConfig())
    }

    internal fun createFilesStoragePlugin(
        safStorage: SafStorage,
    ): org.calyxos.backup.storage.api.StoragePlugin {
        return SeedvaultSafStoragePlugin(context, safStorage)
    }

}
