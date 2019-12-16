package com.stevesoltys.seedvault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat.*
import com.stevesoltys.seedvault.settings.SettingsActivity
import java.util.*

private const val CHANNEL_ID_OBSERVER = "NotificationBackupObserver"
private const val CHANNEL_ID_ERROR = "NotificationError"
private const val NOTIFICATION_ID_OBSERVER = 1
private const val NOTIFICATION_ID_ERROR = 2

class BackupNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)!!.apply {
        createNotificationChannel(getObserverChannel())
        createNotificationChannel(getErrorChannel())
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

    private val observerBuilder = Builder(context, CHANNEL_ID_OBSERVER).apply {
        setSmallIcon(R.drawable.ic_cloud_upload)
    }

    private val errorBuilder = Builder(context, CHANNEL_ID_ERROR).apply {
        setSmallIcon(R.drawable.ic_cloud_error)
    }

    fun onBackupUpdate(app: CharSequence, transferred: Int, expected: Int, userInitiated: Boolean) {
        val notification = observerBuilder.apply {
            setContentTitle(context.getString(R.string.notification_title))
            setContentText(app)
            setWhen(Date().time)
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
            setWhen(Date().time)
            priority = if (userInitiated) PRIORITY_DEFAULT else PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    fun onBackupFinished() {
        nm.cancel(NOTIFICATION_ID_OBSERVER)
    }

    fun onBackupError() {
        val intent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
        val actionText = context.getString(R.string.notification_error_action)
        val action = Action(R.drawable.ic_storage, actionText, pendingIntent)
        val notification = errorBuilder.apply {
            setContentTitle(context.getString(R.string.notification_error_title))
            setContentText(context.getString(R.string.notification_error_text))
            setWhen(Date().time)
            setOnlyAlertOnce(true)
            setAutoCancel(true)
            mActions = arrayListOf(action)
        }.build()
        nm.notify(NOTIFICATION_ID_ERROR, notification)
    }

    fun onBackupErrorSeen() {
        nm.cancel(NOTIFICATION_ID_ERROR)
    }

}
