/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.saf.StorageRootResolver.getIcon
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_DAVX5
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_NEXTCLOUD
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_ROUND_SYNC
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import com.stevesoltys.seedvault.ui.storage.StorageOption
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption

private const val DAVX5_PACKAGE = "at.bitfire.davdroid"
private const val DAVX5_ACTIVITY = "at.bitfire.davdroid.ui.webdav.WebdavMountsActivity"
private const val NEXTCLOUD_PACKAGE = "com.nextcloud.client"
private const val NEXTCLOUD_ACTIVITY = "com.owncloud.android.authentication.AuthenticatorActivity"

/**
 * A class for storage option placeholders that need to be shown under certain circumstances.
 * E.g. a way to install an app when needed for restore.
 */
internal class SafStorageOptions(
    private val context: Context,
    private val isRestore: Boolean,
    private val whitelistedAuthorities: Array<String>,
) {

    private val packageManager = context.packageManager

    internal fun checkOrAddExtraRoots(roots: ArrayList<StorageOption>) {
        checkOrAddUsbRoot(roots)
        checkOrAddDavX5Root(roots)
        checkOrAddNextCloudRoot(roots)
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

    /**
     * This adds a fake Dav X5 entry if no real one was found.
     *
     * If Dav X5 is *not* installed,
     * the user will always have the option to install it by clicking the entry.
     *
     * If it *is* installed and this is restore, the user can set up a new account by clicking.
     */
    private fun checkOrAddDavX5Root(roots: ArrayList<StorageOption>) {
        if (doNotInclude(AUTHORITY_DAVX5, roots)) return

        val intent = Intent().apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            setClassName(DAVX5_PACKAGE, DAVX5_ACTIVITY)
        }
        val marketIntent =
            Intent(ACTION_VIEW, Uri.parse("market://details?id=$DAVX5_PACKAGE")).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }
        val isInstalled = packageManager.resolveActivity(intent, 0) != null
        val canInstall = packageManager.resolveActivity(marketIntent, 0) != null
        val summaryRes = if (isInstalled) {
            if (isRestore) R.string.storage_fake_davx5_summary_installed
            else R.string.storage_fake_davx5_summary_unavailable
        } else {
            if (canInstall) R.string.storage_fake_davx5_summary
            else R.string.storage_fake_davx5_summary_unavailable_market
        }
        val root = SafOption(
            authority = AUTHORITY_DAVX5,
            rootId = "fake",
            documentId = "fake",
            icon = getIcon(context, AUTHORITY_DAVX5, "fake", 0),
            title = context.getString(R.string.storage_fake_davx5_title),
            summary = context.getString(summaryRes),
            availableBytes = null,
            isUsb = false,
            requiresNetwork = true,
            enabled = isInstalled || canInstall,
            nonDefaultAction = {
                if (isInstalled) context.startActivity(intent)
                else if (canInstall) context.startActivity(marketIntent)
            }
        )
        roots.add(root)
    }

    /**
     * This adds a fake Nextcloud entry if no real one was found.
     *
     * If Nextcloud is *not* installed,
     * the user will always have the option to install it by clicking the entry.
     *
     * If it *is* installed and this is restore, the user can set up a new account by clicking.
     * FIXME: If this isn't restore, the entry should be disabled,
     *  because we don't know if there's just no account or an activated passcode
     *  (which hides existing accounts).
     */
    private fun checkOrAddNextCloudRoot(roots: ArrayList<StorageOption>) {
        if (doNotInclude(AUTHORITY_NEXTCLOUD, roots)) return

        val intent = Intent().apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            setClassName(NEXTCLOUD_PACKAGE, NEXTCLOUD_ACTIVITY)
            // setting a nc:// Uri prevents FirstRunActivity to show
            data = Uri.parse("nc://login/server:")
            putExtra("onlyAdd", true)
        }
        val marketIntent =
            Intent(ACTION_VIEW, Uri.parse("market://details?id=$NEXTCLOUD_PACKAGE")).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }
        val isInstalled = packageManager.resolveActivity(intent, 0) != null
        val canInstall = packageManager.resolveActivity(marketIntent, 0) != null
        val summaryRes = if (isInstalled) {
            if (isRestore) R.string.storage_fake_nextcloud_summary_installed
            else R.string.storage_fake_nextcloud_summary_unavailable
        } else {
            if (canInstall) R.string.storage_fake_nextcloud_summary
            else R.string.storage_fake_nextcloud_summary_unavailable_market
        }
        val root = SafOption(
            authority = AUTHORITY_NEXTCLOUD,
            rootId = "fake",
            documentId = "fake",
            icon = getIcon(context, AUTHORITY_NEXTCLOUD, "fake", 0),
            title = context.getString(
                R.string.storage_not_recommended,
                context.getString(R.string.storage_fake_nextcloud_title)
            ),
            summary = context.getString(summaryRes),
            availableBytes = null,
            isUsb = false,
            requiresNetwork = true,
            enabled = isInstalled || canInstall,
            nonDefaultAction = {
                if (isInstalled) context.startActivity(intent)
                else if (canInstall) context.startActivity(marketIntent)
            }
        )
        roots.add(root)
    }

    private fun doNotInclude(
        authority: String,
        roots: ArrayList<StorageOption>,
        doNotIncludeIfTrue: ((StorageOption) -> Boolean)? = null,
    ): Boolean {
        if (!isAuthoritySupported(authority)) return true
        for (root in roots) {
            if (root !is SafOption) continue
            if (root.authority == authority && doNotIncludeIfTrue?.invoke(root) != false) {
                return true
            }
        }
        return false
    }

    private fun isAuthoritySupported(authority: String): Boolean {
        // just restrict where to store backups,
        // restoring can be more free for forward compatibility
        return isRestore || whitelistedAuthorities.contains(authority)
    }

}
