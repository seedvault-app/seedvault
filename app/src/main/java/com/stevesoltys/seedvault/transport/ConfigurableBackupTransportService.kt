package com.stevesoltys.seedvault.transport

import android.app.Service
import android.app.backup.BackupManager
import android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP
import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.app.backup.IBackupManager
import android.content.Context
import android.content.Context.BACKUP_SERVICE
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.notification.NotificationBackupObserver
import org.koin.core.KoinComponent
import org.koin.core.context.GlobalContext.get
import org.koin.core.inject

private val TAG = ConfigurableBackupTransportService::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransportService : Service(), KoinComponent {

    private var transport: ConfigurableBackupTransport? = null

    private val notificationManager: BackupNotificationManager by inject()

    override fun onCreate() {
        super.onCreate()
        transport = ConfigurableBackupTransport(applicationContext)
        Log.d(TAG, "Service created.")
    }

    override fun onBind(intent: Intent): IBinder {
        val transport = this.transport ?: throw IllegalStateException("no transport in onBind()")
        return transport.binder.apply {
            Log.d(TAG, "Transport bound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.onBackupBackgroundFinished()
        transport = null
        Log.d(TAG, "Service destroyed.")
    }

}

@WorkerThread
fun requestBackup(context: Context) {
    val packageService: PackageService = get().koin.get()
    val packages = packageService.eligiblePackages
    val appTotals = packageService.expectedAppTotals

    val observer = NotificationBackupObserver(context, packages.size, appTotals)
    val result = try {
        val backupManager: IBackupManager = get().koin.get()
        backupManager.requestBackup(packages, observer, BackupMonitor(), 0)
    } catch (e: RemoteException) {
        Log.e(TAG, "Error during backup: ", e)
        val nm: BackupNotificationManager = get().koin.get()
        nm.onBackupError()
    }
    if (result == BackupManager.SUCCESS) {
        Log.i(TAG, "Backup succeeded ")
    } else {
        Log.e(TAG, "Backup failed: $result")
    }
}
