/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.storage.SeedvaultSafStoragePlugin

class SafFactory(
    private val context: Context,
    private val keyManager: KeyManager,
    private val settingsManager: SettingsManager,
) {

    internal fun createAppStoragePlugin(
        safStorage: SafStorage,
        documentsStorage: DocumentsStorage = DocumentsStorage(context, settingsManager, safStorage),
    ): StoragePlugin<Uri> {
        return DocumentsProviderStoragePlugin(context, documentsStorage)
    }

    internal fun createFilesStoragePlugin(
        safStorage: SafStorage,
        documentsStorage: DocumentsStorage = DocumentsStorage(context, settingsManager, safStorage),
    ): org.calyxos.backup.storage.api.StoragePlugin {
        return SeedvaultSafStoragePlugin(context, documentsStorage, keyManager)
    }

}
