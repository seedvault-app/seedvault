/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.UserHandle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
import android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Root.COLUMN_FLAGS
import android.provider.DocumentsContract.Root.COLUMN_ICON
import android.provider.DocumentsContract.Root.COLUMN_ROOT_ID
import android.provider.DocumentsContract.Root.COLUMN_SUMMARY
import android.provider.DocumentsContract.Root.COLUMN_TITLE
import android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY
import android.provider.DocumentsContract.Root.FLAG_REMOVABLE_USB
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_CREATE
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_DAVX5
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_DOWNLOADS
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_NEXTCLOUD
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_ROUND_SYNC
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import com.stevesoltys.seedvault.ui.storage.ROOT_ID_DEVICE
import com.stevesoltys.seedvault.ui.storage.ROOT_ID_HOME
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption

internal object StorageRootResolver {

    private val TAG = StorageRootResolver::class.java.simpleName

    fun getStorageRoots(context: Context, authority: String): List<SafOption> {
        val roots = ArrayList<SafOption>()
        val rootsUri = DocumentsContract.buildRootsUri(authority)

        try {
            context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val root = getStorageRoot(context, authority, cursor)
                    if (root != null) roots.add(root)
                }
            }
            // add special system user roots for USB devices
            val c = context.getStorageContext {
                authority == AUTHORITY_STORAGE && UserHandle.myUserId() != UserHandle.USER_SYSTEM
            }
            // only proceed if we really got a different [Context], e.g. had permission for it
            if (context !== c) {
                c.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        // Pass in [context] since it is used to query package manager for app icons
                        val root = getStorageRoot(context, authority, cursor)
                        // only add USB storage from system user, no others
                        if (root != null && root.isUsb) roots.add(root)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load some roots from $authority", e)
        }
        return roots
    }

    /**
     * Used for getting a SafOption when we lack [MANAGE_DOCUMENTS],
     * since we are not allowed to use [getStorageRoots] in this case.
     */
    fun getFakeStorageRootForUri(context: Context, uri: Uri): SafOption {
        val authority = uri.authority ?: throw AssertionError("No authority in $uri")
        return SafOption(
            authority = authority,
            rootId = ROOT_ID_DEVICE,
            documentId = DocumentsContract.getTreeDocumentId(uri),
            icon = getIcon(context, authority, ROOT_ID_DEVICE, 0),
            title = context.getString(R.string.storage_user_selected_location_title),
            summary = "Please open a bug if you see this",
            availableBytes = null,
            isUsb = false, // FIXME not supported without MANAGE_DOCUMENTS permission
            requiresNetwork = authority != AUTHORITY_STORAGE && authority != AUTHORITY_DOWNLOADS,
        )
    }

    private fun getStorageRoot(context: Context, authority: String, cursor: Cursor): SafOption? {
        val flags = cursor.getInt(COLUMN_FLAGS)
        val supportsCreate = flags and FLAG_SUPPORTS_CREATE != 0
        val supportsIsChild = flags and FLAG_SUPPORTS_IS_CHILD != 0
        if (!supportsCreate || !supportsIsChild) return null
        val rootId = cursor.getString(COLUMN_ROOT_ID)!!
        if (authority == AUTHORITY_STORAGE && rootId == ROOT_ID_HOME) return null
        val documentId = cursor.getString(COLUMN_DOCUMENT_ID) ?: return null
        val isUsb = flags and FLAG_REMOVABLE_USB != 0
        return SafOption(
            authority = authority,
            rootId = rootId,
            documentId = documentId,
            icon = getIcon(context, authority, rootId, cursor.getInt(COLUMN_ICON)),
            title = cursor.getString(COLUMN_TITLE)!!,
            summary = cursor.getString(COLUMN_SUMMARY),
            availableBytes = cursor.getInt(COLUMN_AVAILABLE_BYTES).let { bytes ->
                // AOSP 11+ reports -1 instead of null
                if (bytes == -1) {
                    try {
                        if (isUsb) {
                            StatFs("/mnt/media_rw/${documentId.trimEnd(':')}").availableBytes
                        } else if (authority == AUTHORITY_STORAGE && rootId == ROOT_ID_DEVICE) {
                            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
                        } else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting available bytes for $rootId ", e)
                        null
                    }
                } else bytes.toLong()
            },
            isUsb = isUsb,
            requiresNetwork = flags and FLAG_LOCAL_ONLY == 0 // not local only == requires network
        )
    }

    private fun Cursor.getString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index != -1) getString(index) else null
    }

    private fun Cursor.getInt(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index != -1) getInt(index) else 0
    }

    fun getIcon(context: Context, authority: String, rootId: String, icon: Int): Drawable? {
        return getPackageIcon(context, authority, icon) ?: when {
            authority == AUTHORITY_STORAGE && rootId == ROOT_ID_DEVICE -> {
                getDrawable(context, R.drawable.ic_phone_android)
            }

            authority == AUTHORITY_STORAGE && rootId != ROOT_ID_HOME -> {
                getDrawable(context, R.drawable.ic_usb)
            }

            authority == AUTHORITY_NEXTCLOUD -> {
                getDrawable(context, R.drawable.nextcloud)
            }

            authority == AUTHORITY_DAVX5 -> {
                getDrawable(context, R.drawable.davx5)
            }

            authority == AUTHORITY_ROUND_SYNC -> {
                getDrawable(context, R.drawable.round_sync)
            }

            else -> null
        }
    }

    private fun getPackageIcon(context: Context, authority: String, icon: Int): Drawable? {
        if (icon != 0) {
            val pm = context.packageManager
            val info = pm.resolveContentProvider(authority, 0)
            if (info != null) {
                return pm.getDrawable(info.packageName, icon, info.applicationInfo)
            }
        }
        return null
    }

}
