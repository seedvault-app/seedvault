package com.stevesoltys.backup.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.GET_META_DATA
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.provider.DocumentsContract
import android.provider.DocumentsContract.PROVIDER_INTERFACE
import android.provider.DocumentsContract.Root.*
import android.util.Log
import com.stevesoltys.backup.R
import java.lang.Long.parseLong

private val TAG = StorageRootFetcher::class.java.simpleName

const val AUTHORITY_STORAGE = "com.android.externalstorage.documents"
const val ROOT_ID_DEVICE = "primary"
const val ROOT_ID_HOME = "home"

const val AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents"

data class StorageRoot(
        internal val authority: String,
        internal val rootId: String,
        internal val documentId: String,
        internal val icon: Drawable?,
        internal val title: String,
        internal val summary: String?,
        internal val availableBytes: Long?,
        internal val supportsEject: Boolean,
        internal val enabled: Boolean = true) {

    internal val uri: Uri by lazy {
        DocumentsContract.buildTreeDocumentUri(authority, documentId)
    }

    fun isInternal(): Boolean {
        return authority == AUTHORITY_STORAGE && !supportsEject
    }
}

internal interface RemovableStorageListener {
    fun onStorageChanged()
}

internal class StorageRootFetcher(private val context: Context) {

    private val packageManager = context.packageManager
    private val contentResolver = context.contentResolver

    private var listener: RemovableStorageListener? = null
    private val observer = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            listener?.onStorageChanged()
        }
    }

    internal fun setRemovableStorageListener(listener: RemovableStorageListener?) {
        this.listener = listener
        if (listener != null) {
            val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE)
            contentResolver.registerContentObserver(rootsUri, true, observer)
        } else {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    internal fun getRemovableStorageListener() = listener

    internal fun getStorageRoots(): List<StorageRoot> {
        val roots = ArrayList<StorageRoot>()
        val intent = Intent(PROVIDER_INTERFACE)
        val providers = packageManager.queryIntentContentProviders(intent, 0)
        for (info in providers) {
            val providerInfo = info.providerInfo
            val authority = providerInfo.authority
            if (authority != null) {
                roots.addAll(getRoots(providerInfo))
            }
        }
        checkOrAddUsbRoot(roots)
        return roots
    }

    private fun getRoots(providerInfo: ProviderInfo): List<StorageRoot> {
        val authority = providerInfo.authority
        val provider = packageManager.resolveContentProvider(authority, GET_META_DATA)
        if (provider == null || !provider.isSupported()) {
            Log.w(TAG, "Failed to get provider info for $authority")
            return emptyList()
        }

        val roots = ArrayList<StorageRoot>()
        val rootsUri = DocumentsContract.buildRootsUri(authority)

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(rootsUri, null, null, null, null)
            while (cursor.moveToNext()) {
                val root = getStorageRoot(authority, cursor)
                if (root != null) roots.add(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load some roots from $authority", e)
        } finally {
            cursor?.close()
        }
        return roots
    }

    private fun getStorageRoot(authority: String, cursor: Cursor): StorageRoot? {
        val flags = cursor.getInt(COLUMN_FLAGS)
        val supportsCreate = flags and FLAG_SUPPORTS_CREATE != 0
        val supportsIsChild = flags and FLAG_SUPPORTS_IS_CHILD != 0
        if (!supportsCreate || !supportsIsChild) return null
        val rootId = cursor.getString(COLUMN_ROOT_ID)!!
        if (authority == AUTHORITY_STORAGE && rootId == ROOT_ID_HOME) return null
        val supportsEject = flags and FLAG_SUPPORTS_EJECT != 0
        return StorageRoot(
                authority = authority,
                rootId = rootId,
                documentId = cursor.getString(COLUMN_DOCUMENT_ID)!!,
                icon = getIcon(context, authority, rootId, cursor.getInt(COLUMN_ICON)),
                title = cursor.getString(COLUMN_TITLE)!!,
                summary = cursor.getString(COLUMN_SUMMARY),
                availableBytes = cursor.getLong(COLUMN_AVAILABLE_BYTES),
                supportsEject = supportsEject
        )
    }

    private fun checkOrAddUsbRoot(roots: ArrayList<StorageRoot>) {
        for (root in roots) {
            if (root.authority == AUTHORITY_STORAGE && root.supportsEject) return
        }
        val root = StorageRoot(
                authority = AUTHORITY_STORAGE,
                rootId = "usb",
                documentId = "fake",
                icon = getIcon(context, AUTHORITY_STORAGE, "usb", 0),
                title = context.getString(R.string.storage_fake_drive_title),
                summary = context.getString(R.string.storage_fake_drive_summary),
                availableBytes = null,
                supportsEject = true,
                enabled = false
        )
        roots.add(root)
    }

    private fun ProviderInfo.isSupported(): Boolean {
        return if (!exported) {
            Log.w(TAG, "Provider is not exported")
            false
        } else if (!grantUriPermissions) {
            Log.w(TAG, "Provider doesn't grantUriPermissions")
            false
        } else if (MANAGE_DOCUMENTS != readPermission || MANAGE_DOCUMENTS != writePermission) {
            Log.w(TAG, "Provider is not protected by MANAGE_DOCUMENTS")
            false
        } else if (authority == AUTHORITY_DOWNLOADS) {
            Log.w(TAG, "Not supporting $AUTHORITY_DOWNLOADS")
            false
        } else true
    }

    private fun Cursor.getString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index != -1) getString(index) else null
    }

    private fun Cursor.getInt(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index != -1) getInt(index) else 0
    }

    private fun Cursor.getLong(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index == -1) return null
        val value = getString(index) ?: return null
        return try {
            parseLong(value)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun getIcon(context: Context, authority: String, rootId: String, icon: Int): Drawable? {
        return getPackageIcon(context, authority, icon) ?: when {
            authority == AUTHORITY_STORAGE && rootId == ROOT_ID_DEVICE -> context.getDrawable(R.drawable.ic_phone_android)
            authority == AUTHORITY_STORAGE && rootId != ROOT_ID_HOME -> context.getDrawable(R.drawable.ic_usb)
            else -> null
        }
    }

    private fun getPackageIcon(context: Context, authority: String?, icon: Int): Drawable? {
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
