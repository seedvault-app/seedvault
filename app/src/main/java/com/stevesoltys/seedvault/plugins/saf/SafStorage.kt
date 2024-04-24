/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Root.COLUMN_ROOT_ID
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.plugins.StorageProperties

data class SafStorage(
    override val config: Uri,
    override val name: String,
    override val isUsb: Boolean,
    override val requiresNetwork: Boolean,
    /**
     * The [COLUMN_ROOT_ID] for the [uri].
     * This is only nullable for historic reasons, because we didn't always store it.
     */
    val rootId: String?,
) : StorageProperties<Uri>() {

    val uri: Uri = config

    fun getDocumentFile(context: Context) = DocumentFile.fromTreeUri(context, config)
        ?: throw AssertionError("Should only happen on API < 21.")

    /**
     * Returns true if this is USB storage that is not available, false otherwise.
     *
     * Must be run off UI thread (ideally I/O).
     */
    @WorkerThread
    override fun isUnavailableUsb(context: Context): Boolean {
        return isUsb && !getDocumentFile(context).isDirectory
    }
}
