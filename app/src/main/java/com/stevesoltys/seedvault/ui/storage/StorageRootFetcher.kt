package com.stevesoltys.seedvault.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager.GET_META_DATA
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.provider.DocumentsContract
import android.provider.DocumentsContract.PROVIDER_INTERFACE
import android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
import android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Root.COLUMN_FLAGS
import android.provider.DocumentsContract.Root.COLUMN_ICON
import android.provider.DocumentsContract.Root.COLUMN_ROOT_ID
import android.provider.DocumentsContract.Root.COLUMN_SUMMARY
import android.provider.DocumentsContract.Root.COLUMN_TITLE
import android.provider.DocumentsContract.Root.FLAG_REMOVABLE_USB
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_CREATE
import android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
import android.util.Log
import com.stevesoltys.seedvault.R
import java.lang.Long.parseLong

private val TAG = StorageRootFetcher::class.java.simpleName

const val AUTHORITY_STORAGE = "com.android.externalstorage.documents"
const val ROOT_ID_DEVICE = "primary"
const val ROOT_ID_HOME = "home"

const val AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents"
const val AUTHORITY_NEXTCLOUD = "org.nextcloud.documents"

private const val NEXTCLOUD_PACKAGE = "com.nextcloud.client"
private const val NEXTCLOUD_ACTIVITY = "com.owncloud.android.authentication.AuthenticatorActivity"

data class StorageRoot(
        internal val authority: String,
        internal val rootId: String,
        internal val documentId: String,
        internal val icon: Drawable?,
        internal val title: String,
        internal val summary: String?,
        internal val availableBytes: Long?,
        internal val isUsb: Boolean,
        internal val enabled: Boolean = true,
        internal val overrideClickListener: (() -> Unit)? = null) {

    internal val uri: Uri by lazy {
        DocumentsContract.buildTreeDocumentUri(authority, documentId)
    }

    fun isInternal(): Boolean {
        return authority == AUTHORITY_STORAGE && !isUsb
    }
}

internal interface RemovableStorageListener {
    fun onStorageChanged()
}

internal class StorageRootFetcher(private val context: Context, private val isRestore: Boolean) {

    private val packageManager = context.packageManager
    private val contentResolver = context.contentResolver
    private val whitelistedAuthorities = context.resources.getStringArray(R.array.storage_authority_whitelist)

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
        checkOrAddNextCloudRoot(roots)
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
            while (cursor!!.moveToNext()) {
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
        return StorageRoot(
                authority = authority,
                rootId = rootId,
                documentId = cursor.getString(COLUMN_DOCUMENT_ID)!!,
                icon = getIcon(context, authority, rootId, cursor.getInt(COLUMN_ICON)),
                title = cursor.getString(COLUMN_TITLE)!!,
                summary = cursor.getString(COLUMN_SUMMARY),
                availableBytes = cursor.getLong(COLUMN_AVAILABLE_BYTES),
                isUsb = flags and FLAG_REMOVABLE_USB != 0
        )
    }

    private fun checkOrAddUsbRoot(roots: ArrayList<StorageRoot>) {
        if (!isAuthoritySupported(AUTHORITY_STORAGE)) return

        for (root in roots) {
            // return if we already have a USB storage root
            if (root.authority == AUTHORITY_STORAGE && root.isUsb) return
        }
        val root = StorageRoot(
                authority = AUTHORITY_STORAGE,
                rootId = "usb",
                documentId = "fake",
                icon = getIcon(context, AUTHORITY_STORAGE, "usb", 0),
                title = context.getString(R.string.storage_fake_drive_title),
                summary = context.getString(R.string.storage_fake_drive_summary),
                availableBytes = null,
                isUsb = true,
                enabled = false
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
     * If this isn't restore, the entry will be disabled,
     * because we don't know if there's no account or an activated passcode.
     */
    private fun checkOrAddNextCloudRoot(roots: ArrayList<StorageRoot>) {
        for (root in roots) {
            // return if we already have a NextCloud storage root
            if (root.authority == AUTHORITY_NEXTCLOUD) return
        }
        val intent = Intent().apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            setClassName(NEXTCLOUD_PACKAGE, NEXTCLOUD_ACTIVITY)
            // setting a nc:// Uri prevents FirstRunActivity to show
            data = Uri.parse("nc://login/server:")
            putExtra("onlyAdd", true)
        }
        val isInstalled = packageManager.resolveActivity(intent, 0) != null
        val summaryRes = if (isInstalled) {
            if (isRestore) R.string.storage_fake_nextcloud_summary_installed
            else R.string.storage_fake_nextcloud_summary_unavailable
        } else R.string.storage_fake_nextcloud_summary
        val root = StorageRoot(
                authority = AUTHORITY_NEXTCLOUD,
                rootId = "fake",
                documentId = "fake",
                icon = getIcon(context, AUTHORITY_NEXTCLOUD, "fake", 0),
                title = context.getString(R.string.storage_fake_nextcloud_title),
                summary = context.getString(summaryRes),
                availableBytes = null,
                isUsb = false,
                enabled = !isInstalled || isRestore,
                overrideClickListener = {
                    if (isInstalled) context.startActivity(intent)
                    else {
                        val uri = Uri.parse("market://details?id=$NEXTCLOUD_PACKAGE")
                        val i = Intent(ACTION_VIEW, uri)
                        i.addFlags(FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }
                }
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
        } else if (!isAuthoritySupported(authority)) {
            Log.w(TAG, "Authority $authority is not white-listed, ignoring...")
            false
        } else true
    }

    private fun isAuthoritySupported(authority: String): Boolean {
        // just restrict where to store backups, restoring can be more free for forward compatibility
        return isRestore || whitelistedAuthorities.contains(authority)
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
            authority == AUTHORITY_NEXTCLOUD -> context.getDrawable(R.drawable.nextcloud)
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
