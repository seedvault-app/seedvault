package com.stevesoltys.seedvault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.*
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import androidx.core.app.NotificationCompat.*
import com.stevesoltys.seedvault.restore.ACTION_RESTORE_ERROR_UNINSTALL
import com.stevesoltys.seedvault.restore.EXTRA_PACKAGE_NAME
import com.stevesoltys.seedvault.restore.REQUEST_CODE_UNINSTALL
import com.stevesoltys.seedvault.settings.SettingsActivity

private const val CHANNEL_ID_OBSERVER = "NotificationBackupObserver"
private const val CHANNEL_ID_ERROR = "NotificationError"
private const val CHANNEL_ID_RESTORE_ERROR = "NotificationRestoreError"
private const val NOTIFICATION_ID_OBSERVER = 1
private const val NOTIFICATION_ID_ERROR = 2
private const val NOTIFICATION_ID_RESTORE_ERROR = 3

class BackupNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)!!.apply {
        createNotificationChannel(getObserverChannel())
        createNotificationChannel(getErrorChannel())
        createNotificationChannel(getRestoreErrorChannel())
    }

    private fun getObserverChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_channel_title)
        return NotificationChannel(CHANNEL_ID_OBSERVER, title, IMPORTANCE_LOW).apply {
            enableVibration(false)
        }
    }

    private fun getErrorChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_error_channel_title)
        return NotificationChannel(CHANNEL_ID_ERROR, title, IMPORTANCE_DEFAULT)
    }

    private fun getRestoreErrorChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_restore_error_channel_title)
        return NotificationChannel(CHANNEL_ID_RESTORE_ERROR, title, IMPORTANCE_HIGH)
    }

    private val observerBuilder = Builder(context, CHANNEL_ID_OBSERVER).apply {
        setSmallIcon(R.drawable.ic_cloud_upload)
    }

    private val errorBuilder = Builder(context, CHANNEL_ID_ERROR).apply {
        setSmallIcon(R.drawable.ic_cloud_error)
    }

    private val restoreErrorBuilder = Builder(context, CHANNEL_ID_RESTORE_ERROR).apply {
        setSmallIcon(R.drawable.ic_cloud_error)
    }

    fun onBackupUpdate(app: CharSequence, transferred: Int, expected: Int, userInitiated: Boolean) {
        val notification = observerBuilder.apply {
            setContentTitle(context.getString(R.string.notification_title))
            setContentText(app)
            setOngoing(true)
            setShowWhen(false)
            setWhen(System.currentTimeMillis())
            setProgress(expected, transferred, false)
            priority = if (userInitiated) PRIORITY_DEFAULT else PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    fun onBackupResult(app: CharSequence, status: Int, userInitiated: Boolean) {
        val title = context.getString(when (status) {
            0 -> R.string.notification_backup_result_complete
            TRANSPORT_PACKAGE_REJECTED -> R.string.notification_backup_result_rejected
            else -> R.string.notification_backup_result_error
        })
        val notification = observerBuilder.apply {
            setContentTitle(title)
            setContentText(app)
            setOngoing(true)
            setShowWhen(false)
            setWhen(System.currentTimeMillis())
            priority = if (userInitiated) PRIORITY_DEFAULT else PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    fun onBackupFinished(success: Boolean, notBackedUp: Int?, userInitiated: Boolean) {
        if (!userInitiated) {
            nm.cancel(NOTIFICATION_ID_OBSERVER)
            return
        }
        val titleRes = if (success) R.string.notification_success_title else R.string.notification_failed_title
        val contentText = if (notBackedUp == null) null else {
            context.getString(R.string.notification_success_num_not_backed_up, notBackedUp)
        }
        val notification = observerBuilder.apply {
            setContentTitle(context.getString(titleRes))
            setContentText(contentText)
            setOngoing(false)
            setShowWhen(true)
            setWhen(System.currentTimeMillis())
            setProgress(0, 0, false)
            priority = PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    fun onBackupError() {
        val intent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
        val actionText = context.getString(R.string.notification_error_action)
        val action = Action(R.drawable.ic_storage, actionText, pendingIntent)
        val notification = errorBuilder.apply {
            setContentTitle(context.getString(R.string.notification_error_title))
            setContentText(context.getString(R.string.notification_error_text))
            setWhen(System.currentTimeMillis())
            setOnlyAlertOnce(true)
            setAutoCancel(true)
            mActions = arrayListOf(action)
        }.build()
        nm.notify(NOTIFICATION_ID_ERROR, notification)
    }

    fun onBackupErrorSeen() {
        nm.cancel(NOTIFICATION_ID_ERROR)
    }

    fun onRemovableStorageNotAvailableForRestore(packageName: String, storageName: String) {
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo)
        } catch (e: NameNotFoundException) {
            packageName
        }
        val intent = Intent(ACTION_RESTORE_ERROR_UNINSTALL).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_UNINSTALL, intent, FLAG_UPDATE_CURRENT)
        val actionText = context.getString(R.string.notification_restore_error_action)
        val action = Action(R.drawable.ic_warning, actionText, pendingIntent)
        val notification = restoreErrorBuilder.apply {
            setContentTitle(context.getString(R.string.notification_restore_error_title, appName))
            setContentText(context.getString(R.string.notification_restore_error_text, storageName))
            setWhen(System.currentTimeMillis())
            setAutoCancel(true)
            priority = PRIORITY_HIGH
            mActions = arrayListOf(action)
        }.build()
        nm.notify(NOTIFICATION_ID_RESTORE_ERROR, notification)
    }

    fun onRestoreErrorSeen() {
        nm.cancel(NOTIFICATION_ID_RESTORE_ERROR)
    }

}
