package com.stevesoltys.seedvault.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.GET_META_DATA
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract.PROVIDER_INTERFACE
import android.provider.DocumentsContract.buildRootsUri
import android.util.Log
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption

private val TAG = StorageRootFetcher::class.java.simpleName

const val AUTHORITY_STORAGE = "com.android.externalstorage.documents"
const val ROOT_ID_DEVICE = "primary"
const val ROOT_ID_HOME = "home"

const val AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents"
const val AUTHORITY_NEXTCLOUD = "org.nextcloud.documents"
const val AUTHORITY_DAVX5 = "at.bitfire.davdroid.webdav"

internal interface RemovableStorageListener {
    fun onStorageChanged()
}

internal class StorageRootFetcher(private val context: Context, private val isRestore: Boolean) {

    private val packageManager = context.packageManager
    private val contentResolver = context.contentResolver
    private val whitelistedAuthorities =
        context.resources.getStringArray(R.array.storage_authority_whitelist)
    private val safStorageOptions = SafStorageOptions(context, isRestore, whitelistedAuthorities)

    private var listener: RemovableStorageListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            listener?.onStorageChanged()
        }
    }

    internal fun setRemovableStorageListener(listener: RemovableStorageListener?) {
        this.listener = listener
        if (listener != null) {
            val rootsUri = buildRootsUri(AUTHORITY_STORAGE)
            contentResolver.registerContentObserver(rootsUri, true, observer)
        } else {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    internal fun getRemovableStorageListener() = listener

    internal fun getStorageOptions(): List<StorageOption> {
        val roots = ArrayList<SafOption>()
        val intent = Intent(PROVIDER_INTERFACE)
        val providers = packageManager.queryIntentContentProviders(intent, 0)
        for (info in providers) {
            val providerInfo = info.providerInfo
            val authority = providerInfo.authority
            if (authority != null) {
                roots.addAll(getRoots(providerInfo))
            }
        }
        // there's a couple of options, we still want to show, even if no roots are found for them
        safStorageOptions.checkOrAddExtraRoots(roots)
        return roots
    }

    private fun getRoots(providerInfo: ProviderInfo): List<SafOption> {
        val authority = providerInfo.authority
        val provider = packageManager.resolveContentProvider(authority, GET_META_DATA)
        return if (provider == null || !provider.isSupported()) {
            Log.w(TAG, "Failed to get provider info for $authority")
            emptyList()
        } else {
            StorageRootResolver.getStorageRoots(context, authority)
        }
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
        // just restrict where to store backups,
        // restoring can be more free for forward compatibility
        return isRestore || whitelistedAuthorities.contains(authority)
    }

}
