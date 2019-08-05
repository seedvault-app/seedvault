package com.stevesoltys.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.backup.BackupProgress
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.IBackupObserver
import android.content.Context
import android.util.Log
import android.util.Log.INFO
import android.util.Log.isLoggable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_DEFAULT
import androidx.core.app.NotificationCompat.PRIORITY_LOW

private const val CHANNEL_ID = "NotificationBackupObserver"
private const val NOTIFICATION_ID = 1042

private val TAG = NotificationBackupObserver::class.java.simpleName

class NotificationBackupObserver(
        private val context: Context,
        private val userInitiated: Boolean) : IBackupObserver.Stub() {

    private val pm = context.packageManager
    private val nm = context.getSystemService(NotificationManager::class.java).apply {
        val title = context.getString(R.string.notification_channel_title)
        val channel = NotificationChannel(CHANNEL_ID, title, IMPORTANCE_LOW).apply {
            enableVibration(false)
        }
        createNotificationChannel(channel)
    }
    private val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID).apply {
        setSmallIcon(R.drawable.ic_cloud_upload)
        priority = if (userInitiated) PRIORITY_DEFAULT else PRIORITY_LOW
    }

    /**
     * This method could be called several times for packages with full data backup.
     * It will tell how much of backup data is already saved and how much is expected.
     *
     * @param currentBackupPackage The name of the package that now being backed up.
     * @param backupProgress Current progress of backup for the package.
     */
    override fun onUpdate(currentBackupPackage: String, backupProgress: BackupProgress) {
        val transferred = backupProgress.bytesTransferred
        val expected = backupProgress.bytesExpected
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Update. Target: $currentBackupPackage, $transferred/$expected")
        }
        val notification = notificationBuilder.apply {
            setContentTitle(context.getString(R.string.notification_title))
            setContentText(getAppName(currentBackupPackage))
            setProgress(expected.toInt(), transferred.toInt(), false)
        }.build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Backup of one package or initialization of one transport has completed.  This
     * method will be called at most one time for each package or transport, and might not
     * be not called if the operation fails before backupFinished(); for example, if the
     * requested package/transport does not exist.
     *
     * @param target The name of the package that was backed up, or of the transport
     *                  that was initialized
     * @param status Zero on success; a nonzero error code if the backup operation failed.
     */
    override fun onResult(target: String, status: Int) {
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Completed. Target: $target, status: $status")
        }
        val title = context.getString(when (status) {
            0 -> R.string.notification_backup_result_complete
            TRANSPORT_PACKAGE_REJECTED -> R.string.notification_backup_result_rejected
            else -> R.string.notification_backup_result_error
        })
        val notification = notificationBuilder.apply {
            setContentTitle(title)
            setContentText(getAppName(target))
        }.build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * The backup process has completed.  This method will always be called,
     * even if no individual package backup operations were attempted.
     *
     * @param status Zero on success; a nonzero error code if the backup operation
     *   as a whole failed.
     */
    override fun backupFinished(status: Int) {
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Backup finished. Status: $status")
        }
        nm.cancel(NOTIFICATION_ID)
    }

    private fun getAppName(packageId: String): CharSequence {
        if (packageId == "@pm@") return packageId
        val appInfo = pm.getApplicationInfo(packageId, 0)
        return pm.getApplicationLabel(appInfo)
    }

}
