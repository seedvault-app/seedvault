package com.stevesoltys.backup.transport

import android.app.Service
import android.app.backup.BackupManager
import android.app.backup.BackupTransport
import android.content.Context
import android.content.Context.BACKUP_SERVICE
import android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP
import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.NotificationBackupObserver
import com.stevesoltys.backup.service.PackageService
import com.stevesoltys.backup.session.backup.BackupMonitor

private val TAG = ConfigurableBackupTransportService::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransportService : Service() {

    private var transport: ConfigurableBackupTransport? = null

    override fun onCreate() {
        super.onCreate()
        transport = ConfigurableBackupTransport(applicationContext)
        Log.d(TAG, "Service created.")
    }

    override fun onBind(intent: Intent): IBinder {
        val transport = this.transport ?: throw IllegalStateException()
        return transport.binder.apply {
            Log.d(TAG, "Transport bound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transport = null
        Log.d(TAG, "Service destroyed.")
    }

}

@WorkerThread
fun requestBackup(context: Context) {
    context.startService(Intent(context, ConfigurableBackupTransportService::class.java))
    val observer = NotificationBackupObserver(context, true)
    val flags = FLAG_NON_INCREMENTAL_BACKUP or FLAG_USER_INITIATED
    val packages = PackageService().eligiblePackages
    val result = try {
        Backup.backupManager.requestBackup(packages, observer, BackupMonitor(), flags)
    } catch (e: RemoteException) {
        // TODO show notification on backup error
        Log.e(TAG, "Error during backup: ", e)
    }
    if (result == BackupManager.SUCCESS) {
        Log.i(TAG, "Backup succeeded ")
    } else {
        Log.e(TAG, "Backup failed: $result")
    }
}
