package com.stevesoltys.seedvault.transport.restore

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.Context
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
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.FAILED
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.SUCCEEDED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.IOException

private val TAG: String = ApkInstaller::class.java.simpleName

private const val BROADCAST_ACTION = "com.android.packageinstaller.ACTION_INSTALL_COMMIT"

internal class ApkInstaller(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val installer: PackageInstaller = pm.packageInstaller

    @ExperimentalCoroutinesApi
    @Throws(IOException::class, SecurityException::class)
    internal fun install(cachedApk: File, packageName: String, installerPackageName: String?, installResult: MutableInstallResult) = callbackFlow {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                if (i.action != BROADCAST_ACTION) return
                offer(onBroadcastReceived(i, packageName, cachedApk, installResult))
                close()
            }
        }
        context.registerReceiver(broadcastReceiver, IntentFilter(BROADCAST_ACTION))

        install(cachedApk, installerPackageName)

        awaitClose { context.unregisterReceiver(broadcastReceiver) }
    }

    private fun install(cachedApk: File, installerPackageName: String?) {
        val sessionParams = SessionParams(MODE_FULL_INSTALL).apply {
            setInstallerPackageName(installerPackageName)
        }
        // Don't set more sessionParams intentionally here.
        // We saw strange permission issues when doing setInstallReason() or setting installFlags.
        @Suppress("BlockingMethodInNonBlockingContext")  // flows on Dispatcher.IO
        val session = installer.openSession(installer.createSession(sessionParams))
        val sizeBytes = cachedApk.length()
        session.use { s ->
            cachedApk.inputStream().use { inputStream ->
                s.openWrite("PackageInstaller", 0, sizeBytes).use { out ->
                    inputStream.copyTo(out)
                    s.fsync(out)
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
        val pendingIntent = PendingIntent.getBroadcast(context, 0, broadcastIntent, FLAG_UPDATE_CURRENT)
        return pendingIntent.intentSender
    }

    private fun onBroadcastReceived(i: Intent, expectedPackageName: String, cachedApk: File, installResult: MutableInstallResult): InstallResult {
        val packageName = i.getStringExtra(EXTRA_PACKAGE_NAME)!!
        val success = i.getIntExtra(EXTRA_STATUS, -1) == STATUS_SUCCESS
        val statusMsg = i.getStringExtra(EXTRA_STATUS_MESSAGE)!!

        check(packageName == expectedPackageName) { "Expected $expectedPackageName, but got $packageName." }
        Log.d(TAG, "Received result for $packageName: success=$success $statusMsg")

        // delete cached APK file
        cachedApk.delete()

        // update status and offer result
        val status = if (success) SUCCEEDED else FAILED
        return installResult.update(packageName) { it.copy(status = status) }
    }

}
