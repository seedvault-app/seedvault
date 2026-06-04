/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import android.content.Context
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.saf.StorageRootResolver.getIcon
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_ROUND_SYNC
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import com.stevesoltys.seedvault.ui.storage.StorageOption
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption

/**
 * A class for storage option placeholders that need to be shown under certain circumstances.
 * E.g. a way to install an app when needed for restore.
 */
internal class SafStorageOptions(
    private val context: Context,
) {

    internal fun checkOrAddExtraRoots(roots: ArrayList<StorageOption>) {
        checkOrAddUsbRoot(roots)
        checkOrAddRoundSyncRoots(roots)
    }

    private fun checkOrAddUsbRoot(roots: ArrayList<StorageOption>) {
        if (doNotInclude(AUTHORITY_STORAGE, roots) { it is SafOption && it.isUsb }) return

        val root = SafOption(
            authority = AUTHORITY_STORAGE,
            rootId = "usb",
            documentId = "fake",
            icon = getIcon(context, AUTHORITY_STORAGE, "usb", 0),
            title = context.getString(R.string.storage_fake_drive_title),
            summary = context.getString(R.string.storage_fake_drive_summary),
            availableBytes = null,
            isUsb = true,
            requiresNetwork = false,
            enabled = false
        )
        roots.add(root)
    }

    /**
     * Add a storage root for each child directory at the RoundSync root, if it exists.
     */
    private fun checkOrAddRoundSyncRoots(roots: ArrayList<StorageOption>) {
        val roundSyncRoot = roots.firstOrNull {
            it is SafOption && it.authority == AUTHORITY_ROUND_SYNC
        } as? SafOption ?: return

        roots.remove(roundSyncRoot)

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            roundSyncRoot.uri, roundSyncRoot.documentId
        )
        val projection = arrayOf(COLUMN_DISPLAY_NAME, COLUMN_DOCUMENT_ID)
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)

        cursor?.use {
            val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
            val documentIdIndex = cursor.getColumnIndex(COLUMN_DOCUMENT_ID)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                val documentId = cursor.getString(documentIdIndex)

                val childRoot = SafOption(
                    authority = AUTHORITY_ROUND_SYNC,
                    rootId = name,
                    documentId = documentId,
                    icon = getIcon(context, AUTHORITY_ROUND_SYNC, name, 0),
                    title = name,
                    summary = context.getString(R.string.storage_round_sync_summary_prefix) + name,
                    availableBytes = null,
                    isUsb = false,
                    requiresNetwork = true,
                    enabled = true
                )

                roots.add(childRoot)
            }
        }
    }

    private fun doNotInclude(
        authority: String,
        roots: ArrayList<StorageOption>,
        doNotIncludeIfTrue: ((StorageOption) -> Boolean)? = null,
    ): Boolean {
        for (root in roots) {
            if (root !is SafOption) continue
            if (root.authority == authority && doNotIncludeIfTrue?.invoke(root) != false) {
                return true
            }
        }
        return false
    }
}
