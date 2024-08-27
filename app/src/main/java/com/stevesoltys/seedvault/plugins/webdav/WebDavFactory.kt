/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.webdav.WebDavBackend
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig

class WebDavFactory(
    private val context: Context,
) {

    fun createBackend(config: WebDavConfig): Backend = WebDavBackend(config)

    fun createFilesStoragePlugin(
        config: WebDavConfig,
    ): org.calyxos.backup.storage.api.StoragePlugin {
        @SuppressLint("HardwareIds")
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return com.stevesoltys.seedvault.storage.WebDavStoragePlugin(
            androidId = androidId,
            webDavConfig = config,
        )
    }

}
