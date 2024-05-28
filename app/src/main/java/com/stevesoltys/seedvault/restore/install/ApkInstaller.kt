/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME
import android.content.pm.PackageInstaller.EXTRA_STATUS
import android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE
import android.content.pm.PackageInstaller.STATUS_SUCCESS
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
import android.content.pm.PackageManager
import android.util.Log
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

private val TAG: String = ApkInstaller::class.java.simpleName

private const val BROADCAST_ACTION = "com.android.packageinstaller.ACTION_INSTALL_COMMIT"

internal class ApkInstaller(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val installer: PackageInstaller = pm.packageInstaller

    @Throws(IOException::class, SecurityException::class)
    internal suspend fun install(
        cachedApks: List<File>,
        packageName: String,
        installerPackageName: String?,
        installResult: InstallResult,
    ) = suspendCancellableCoroutine { cont ->
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                if (i.action != BROADCAST_ACTION) return
                context.unregisterReceiver(this)
                cont.resume(onBroadcastReceived(i, packageName, cachedApks, installResult))
            }
        }
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(BROADCAST_ACTION),
            RECEIVER_NOT_EXPORTED,
        )
        cont.invokeOnCancellation { context.unregisterReceiver(broadcastReceiver) }

        install(cachedApks, installerPackageName)
    }

    @SuppressLint("NewApi")
    private fun install(cachedApks: List<File>, installerPackageName: String?) {
        val sessionParams = SessionParams(MODE_FULL_INSTALL).apply {
            setInstallerPackageName(installerPackageName)
            // Setting the INSTALL_ALLOW_TEST flag here does not allow us to install test apps,
            // because the flag is filtered out by PackageInstallerService.
        }
        // Don't set more sessionParams intentionally here.
        // We saw strange permission issues when doing setInstallReason() or setting installFlags.
        val session = installer.openSession(installer.createSession(sessionParams))
        session.use { s ->
            cachedApks.forEach { cachedApk ->
                val sizeBytes = cachedApk.length()
                cachedApk.inputStream().use { inputStream ->
                    s.openWrite(cachedApk.name, 0, sizeBytes).use { out ->
                        inputStream.copyTo(out)
                        s.fsync(out)
                    }
                }
            }
            s.commit(getIntentSender())
        }
    }

    private fun getIntentSender(): IntentSender {
        val broadcastIntent = Intent(BROADCAST_ACTION).apply {
            flags = FLAG_RECEIVER_FOREGROUND
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            broadcastIntent,
            FLAG_UPDATE_CURRENT or FLAG_MUTABLE, // needs to be mutable, otherwise no extras
        )
        return pendingIntent.intentSender
    }

    private fun onBroadcastReceived(
        i: Intent,
        expectedPackageName: String,
        cachedApks: List<File>,
        installResult: InstallResult,
    ): InstallResult {
        val packageName = i.getStringExtra(EXTRA_PACKAGE_NAME)!!
        val success = i.getIntExtra(EXTRA_STATUS, -1) == STATUS_SUCCESS
        val statusMsg = i.getStringExtra(EXTRA_STATUS_MESSAGE)!!

        check(packageName == expectedPackageName) {
            "Expected $expectedPackageName, but got $packageName."
        }
        Log.d(TAG, "Received result for $packageName: success=$success $statusMsg")

        // delete all cached APK files on I/O thread
        GlobalScope.launch(Dispatchers.IO) {
            cachedApks.forEach { it.delete() }
        }

        // update status and offer result
        val status = if (success) SUCCEEDED else FAILED
        return installResult.update(packageName) { it.copy(state = status) }
    }

}
