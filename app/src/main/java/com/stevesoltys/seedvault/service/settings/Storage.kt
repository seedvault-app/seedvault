package com.stevesoltys.seedvault.service.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile

data class Storage(
    val uri: Uri,
    val name: String,
    val isUsb: Boolean,
    val requiresNetwork: Boolean,
) {
    fun getDocumentFile(context: Context) = DocumentFile.fromTreeUri(context, uri)
        ?: throw AssertionError("Should only happen on API < 21.")

    /**
     * Returns true if this is USB storage that is not available, false otherwise.
     *
     * Must be run off UI thread (ideally I/O).
     */
    @WorkerThread
    fun isUnavailableUsb(context: Context): Boolean {
        return isUsb && !getDocumentFile(context).isDirectory
    }

    /**
     * Returns true if this is storage that requires network access,
     * but it isn't available right now.
     */
    fun isUnavailableNetwork(context: Context): Boolean {
        return requiresNetwork && !hasUnmeteredInternet(context)
    }

    private fun hasUnmeteredInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val isMetered = cm.isActiveNetworkMetered
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !isMetered
    }
}
