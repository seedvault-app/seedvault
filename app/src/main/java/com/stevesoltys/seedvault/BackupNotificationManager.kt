package com.stevesoltys.seedvault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationCompat.PRIORITY_DEFAULT
import androidx.core.app.NotificationCompat.PRIORITY_HIGH
import androidx.core.app.NotificationCompat.PRIORITY_LOW
import com.stevesoltys.seedvault.restore.ACTION_RESTORE_ERROR_UNINSTALL
import com.stevesoltys.seedvault.restore.EXTRA_PACKAGE_NAME
import com.stevesoltys.seedvault.restore.REQUEST_CODE_UNINSTALL
import com.stevesoltys.seedvault.settings.ACTION_APP_STATUS_LIST
import com.stevesoltys.seedvault.settings.SettingsActivity

private const val CHANNEL_ID_OBSERVER = "NotificationBackupObserver"
private const val CHANNEL_ID_ERROR = "NotificationError"
private const val CHANNEL_ID_RESTORE_ERROR = "NotificationRestoreError"
private const val NOTIFICATION_ID_OBSERVER = 1
private const val NOTIFICATION_ID_ERROR = 2
private const val NOTIFICATION_ID_RESTORE_ERROR = 3

private val TAG = BackupNotificationManager::class.java.simpleName

class BackupNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)!!.apply {
        createNotificationChannel(getObserverChannel())
        createNotificationChannel(getErrorChannel())
        createNotificationChannel(getRestoreErrorChannel())
    }
    private var expectedApps: Int? = null
    private var expectedOptOutApps: Int? = null
    private var expectedPmRecords: Int? = null

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

    /**
     * Call this right after starting a backup.
     *
     * We can not know [expectedPmRecords] here, because this number varies between backup runs
     * and is only known when the system tells us to update [MAGIC_PACKAGE_MANAGER].
     */
    fun onBackupStarted(
        expectedPackages: Int,
        expectedOptOutPackages: Int,
        userInitiated: Boolean
    ) {
        updateBackupNotification(
            contentText = "", // This passes quickly, no need to show something here
            transferred = 0,
            expected = expectedPackages,
            userInitiated = userInitiated
        )
        expectedApps = expectedPackages
        expectedOptOutApps = expectedOptOutPackages
    }

    /**
     * This is expected to get called before [onOptOutAppBackup] and [onBackupUpdate].
     */
    fun onPmKvBackup(packageName: String, transferred: Int, expected: Int) {
        if (expectedApps == null) {
            Log.d(TAG, "Expected number of apps unknown. Not showing @pm@ notification.")
            return
        }
        val appName = getAppName(context, packageName)
        val contentText = context.getString(R.string.notification_content_package_manager, appName)
        val addend = (expectedOptOutApps ?: 0) + (expectedApps ?: 0)
        updateBackupNotification(
            contentText = contentText,
            transferred = transferred,
            expected = expected + addend,
            userInitiated = false
        )
        expectedPmRecords = expected
    }

    /**
     * This should get called after [onPmKvBackup], but before [onBackupUpdate].
     */
    fun onOptOutAppBackup(packageName: String, transferred: Int, expected: Int) {
        if (expectedApps == null) {
            Log.d(TAG, "Expected number of apps unknown. Not showing APK notification.")
            return
        }
        val appName = getAppName(context, packageName)
        val contentText = context.getString(R.string.notification_content_opt_out_app, appName)
        updateBackupNotification(
            contentText = contentText,
            transferred = transferred + (expectedPmRecords ?: 0),
            expected = expected + (expectedApps ?: 0) + (expectedPmRecords ?: 0),
            userInitiated = false
        )
        expectedOptOutApps = expected
    }

    /**
     * In the series of notification updates,
     * this type is is expected to get called after [onOptOutAppBackup] and [onPmKvBackup].
     */
    fun onBackupUpdate(app: CharSequence, transferred: Int, userInitiated: Boolean) {
        val expected = expectedApps ?: error("expectedApps is null")
        val addend = (expectedOptOutApps ?: 0) + (expectedPmRecords ?: 0)
        updateBackupNotification(
            contentText = app,
            transferred = transferred + addend,
            expected = expected + addend,
            userInitiated = userInitiated
        )
    }

    private fun updateBackupNotification(
        contentText: CharSequence,
        transferred: Int,
        expected: Int,
        userInitiated: Boolean
    ) {
        Log.i(TAG, "$transferred/$expected $contentText")
        val notification = Builder(context, CHANNEL_ID_OBSERVER).apply {
            setSmallIcon(R.drawable.ic_cloud_upload)
            setContentTitle(context.getString(R.string.notification_title))
            setContentText(contentText)
            setOngoing(true)
            setShowWhen(false)
            setWhen(System.currentTimeMillis())
            setProgress(expected, transferred, false)
            priority = if (userInitiated) PRIORITY_DEFAULT else PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    fun onBackupFinished(success: Boolean, notBackedUp: Int?, userInitiated: Boolean) {
        if (!userInitiated) {
            nm.cancel(NOTIFICATION_ID_OBSERVER)
            return
        }
        val titleRes =
            if (success) R.string.notification_success_title else R.string.notification_failed_title
        val contentText = if (notBackedUp == null) null else {
            context.getString(R.string.notification_success_num_not_backed_up, notBackedUp)
        }
        val iconRes = if (success) R.drawable.ic_cloud_done else R.drawable.ic_cloud_error
        val intent = Intent(context, SettingsActivity::class.java).apply {
            if (success) action = ACTION_APP_STATUS_LIST
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
        val notification = Builder(context, CHANNEL_ID_OBSERVER).apply {
            setSmallIcon(iconRes)
            setContentTitle(context.getString(titleRes))
            setContentText(contentText)
            setOngoing(false)
            setShowWhen(true)
            setAutoCancel(true)
            setContentIntent(pendingIntent)
            setWhen(System.currentTimeMillis())
            setProgress(0, 0, false)
            priority = PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
        // reset number of expected apps
        expectedOptOutApps = null
        expectedPmRecords = null
        expectedApps = null
    }

    fun onBackupError() {
        val intent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
        val actionText = context.getString(R.string.notification_error_action)
        val action = Action(R.drawable.ic_storage, actionText, pendingIntent)
        val notification = Builder(context, CHANNEL_ID_ERROR).apply {
            setSmallIcon(R.drawable.ic_cloud_error)
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
        val pendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_CODE_UNINSTALL, intent, FLAG_UPDATE_CURRENT)
        val actionText = context.getString(R.string.notification_restore_error_action)
        val action = Action(R.drawable.ic_warning, actionText, pendingIntent)
        val notification = Builder(context, CHANNEL_ID_RESTORE_ERROR).apply {
            setSmallIcon(R.drawable.ic_cloud_error)
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
