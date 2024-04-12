/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.plugins.StoragePlugin

class WebDavFactory(
    private val context: Context,
    private val keyManager: KeyManager,
) {

    fun createAppStoragePlugin(config: WebDavConfig): StoragePlugin<WebDavConfig> {
        return WebDavStoragePlugin(context, config)
    }

    fun createFilesStoragePlugin(
        config: WebDavConfig,
    ): org.calyxos.backup.storage.api.StoragePlugin {
        @SuppressLint("HardwareIds")
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return com.stevesoltys.seedvault.storage.WebDavStoragePlugin(
            keyManager = keyManager,
            androidId = androidId,
            webDavConfig = config,
        )
    }

}
