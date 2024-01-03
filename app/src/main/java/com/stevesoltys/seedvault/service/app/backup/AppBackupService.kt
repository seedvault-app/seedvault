package com.stevesoltys.seedvault.service.app.backup

import android.app.backup.BackupManager
import android.app.backup.IBackupManager
import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.service.app.BackupManagerOperationMonitor
import com.stevesoltys.seedvault.service.app.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NotificationBackupObserver
import org.koin.core.context.GlobalContext

private val TAG = AppBackupService::class.java.simpleName

internal class AppBackupService(
    private val context: Context,
) {

    fun initiateBackup() {
        requestBackup(context)
    }
}

/**
 * TODO: Move to above service class.
 */
@WorkerThread
fun requestBackup(context: Context) {
    val backupManager: IBackupManager = GlobalContext.get().get()
    if (backupManager.isBackupEnabled) {
        val packageService: PackageService = GlobalContext.get().get()
        val packages = packageService.eligiblePackages
        val appTotals = packageService.expectedAppTotals

        val result = try {
            Log.d(TAG, "Backup is enabled, request backup...")
            val observer = NotificationBackupObserver(context, packages.size, appTotals)
            backupManager.requestBackup(packages, observer, BackupManagerOperationMonitor(), 0)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error during backup: ", e)
            val nm: BackupNotificationManager = GlobalContext.get().get()
            nm.onBackupError()
        }
        if (result == BackupManager.SUCCESS) {
            Log.i(TAG, "Backup succeeded ")
        } else {
            Log.e(TAG, "Backup failed: $result")
        }
    } else {
        Log.i(TAG, "Backup is not enabled")
    }
}
