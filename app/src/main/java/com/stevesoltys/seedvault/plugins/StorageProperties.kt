/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import androidx.annotation.WorkerThread
import at.bitfire.dav4jvm.exception.HttpException
import java.io.IOException

abstract class StorageProperties<T> {
    abstract val config: T
    abstract val name: String
    abstract val isUsb: Boolean
    abstract val requiresNetwork: Boolean

    @WorkerThread
    abstract fun isUnavailableUsb(context: Context): Boolean

    /**
     * Returns true if this is storage that requires network access,
     * but it isn't available right now.
     */
    fun isUnavailableNetwork(context: Context, allowMetered: Boolean): Boolean {
        return requiresNetwork && !hasUnmeteredInternet(context, allowMetered)
    }

    private fun hasUnmeteredInternet(context: Context, allowMetered: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val isMetered = cm.isActiveNetworkMetered
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NET_CAPABILITY_INTERNET) && (allowMetered || !isMetered)
    }
}

fun Exception.isOutOfSpace(): Boolean {
    return when (this) {
        is IOException -> message?.contains("No space left on device") == true ||
            (cause as? HttpException)?.code == 507

        is HttpException -> code == 507

        else -> false
    }
}
