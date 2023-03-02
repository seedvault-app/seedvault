/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import org.calyxos.backup.storage.plugin.saf.SafStoragePlugin
import javax.crypto.SecretKey

internal class SeedvaultSafStoragePlugin(
    private val appContext: Context,
    private val storage: DocumentsStorage,
    private val keyManager: KeyManager,
) : SafStoragePlugin(appContext) {
    /**
     * Attention: This context might be from a different user. Use with care.
     */
    override val context: Context get() = appContext.getStorageContext { storage.safStorage.isUsb }
    override val root: DocumentFile get() = storage.rootBackupDir ?: error("No storage set")

    override fun getMasterKey(): SecretKey = keyManager.getMainKey()
    override fun hasMasterKey(): Boolean = keyManager.hasMainKey()
}
