package com.stevesoltys.backup.service.backup

import android.app.backup.BackupManager
import android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP
import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Context.BACKUP_SERVICE
import android.content.Intent
import android.os.RemoteException
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.NotificationBackupObserver
import com.stevesoltys.backup.service.PackageService
import com.stevesoltys.backup.session.backup.BackupMonitor
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService

private val TAG = BackupJobService::class.java.name

// TODO might not be needed, if the OS really schedules backups on its own
class BackupJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "Triggering full backup")
        try {
            requestFullBackup(this)
        } finally {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        try {
            Backup.backupManager.cancelBackups()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error cancelling backup: ", e)
        }
        return true
    }

}

@WorkerThread
fun requestFullBackup(context: Context) {
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
