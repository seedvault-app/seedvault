/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.storage.SeedvaultSafStoragePlugin

class SafFactory(
    private val context: Context,
) {

    internal fun createAppStoragePlugin(
        safStorage: SafStorage,
    ): StoragePlugin<Uri> {
        return DocumentsProviderStoragePlugin(context, safStorage)
    }

    internal fun createFilesStoragePlugin(
        safStorage: SafStorage,
    ): org.calyxos.backup.storage.api.StoragePlugin {
        return SeedvaultSafStoragePlugin(context, safStorage)
    }

}
