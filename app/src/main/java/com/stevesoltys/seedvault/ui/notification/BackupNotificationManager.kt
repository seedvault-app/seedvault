package com.stevesoltys.seedvault.ui.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
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
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.ACTION_RESTORE_ERROR_UNINSTALL
import com.stevesoltys.seedvault.restore.EXTRA_PACKAGE_NAME
import com.stevesoltys.seedvault.restore.REQUEST_CODE_UNINSTALL
import com.stevesoltys.seedvault.settings.ACTION_APP_STATUS_LIST
import com.stevesoltys.seedvault.settings.SettingsActivity
import com.stevesoltys.seedvault.transport.backup.ExpectedAppTotals

private const val CHANNEL_ID_OBSERVER = "NotificationBackupObserver"
private const val CHANNEL_ID_ERROR = "NotificationError"
private const val CHANNEL_ID_RESTORE_ERROR = "NotificationRestoreError"
private const val NOTIFICATION_ID_OBSERVER = 1
private const val NOTIFICATION_ID_ERROR = 2
private const val NOTIFICATION_ID_RESTORE_ERROR = 3
private const val NOTIFICATION_ID_BACKGROUND = 4
private const val NOTIFICATION_ID_NO_MAIN_KEY_ERROR = 5

private val TAG = BackupNotificationManager::class.java.simpleName

internal class BackupNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)!!.apply {
        createNotificationChannel(getObserverChannel())
        createNotificationChannel(getErrorChannel())
        createNotificationChannel(getRestoreErrorChannel())
    }
    private var expectedApps: Int? = null
    private var expectedOptOutApps: Int? = null
    private var expectedAppTotals: ExpectedAppTotals? = null

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
     */
    fun onBackupStarted(
        expectedPackages: Int,
        appTotals: ExpectedAppTotals,
    ) {
        updateBackupNotification(
            infoText = "", // This passes quickly, no need to show something here
            transferred = 0,
            expected = expectedPackages
        )
        expectedApps = expectedPackages
        expectedOptOutApps = appTotals.appsOptOut
        expectedAppTotals = appTotals
    }

    /**
     * This should get called before [onBackupUpdate].
     */
    fun onOptOutAppBackup(packageName: String, transferred: Int, expected: Int) {
        val text = "Opt-out APK for $packageName"
        if (expectedApps == null) {
            updateBackgroundBackupNotification(text)
        } else {
            updateBackupNotification(text, transferred, expected + (expectedApps ?: 0))
            expectedOptOutApps = expected
        }
    }

    /**
     * In the series of notification updates,
     * this type is is expected to get called after [onOptOutAppBackup].
     */
    fun onBackupUpdate(app: CharSequence, transferred: Int) {
        val expected = expectedApps ?: error("expectedApps is null")
        val addend = expectedOptOutApps ?: 0
        updateBackupNotification(
            infoText = app,
            transferred = transferred + addend,
            expected = expected + addend
        )
    }

    private fun updateBackupNotification(
        infoText: CharSequence,
        transferred: Int,
        expected: Int,
    ) {
        @Suppress("MagicNumber")
        val percentage = (transferred.toFloat() / expected) * 100
        val percentageStr = "%.0f%%".format(percentage)
        Log.i(TAG, "$transferred/$expected - $percentageStr - $infoText")
        val notification = Builder(context, CHANNEL_ID_OBSERVER).apply {
            setSmallIcon(R.drawable.ic_cloud_upload)
            setContentTitle(context.getString(R.string.notification_title))
            setContentText(percentageStr)
            setOngoing(true)
            setShowWhen(false)
            setWhen(System.currentTimeMillis())
            setProgress(expected, transferred, false)
            priority = PRIORITY_DEFAULT
        }.build()
        nm.notify(NOTIFICATION_ID_OBSERVER, notification)
    }

    private fun updateBackgroundBackupNotification(infoText: CharSequence) {
        Log.i(TAG, "$infoText")
        val notification = Builder(context, CHANNEL_ID_OBSERVER).apply {
            setSmallIcon(R.drawable.ic_cloud_upload)
            setContentTitle(context.getString(R.string.notification_title))
            setOngoing(true)
            setShowWhen(false)
            setWhen(System.currentTimeMillis())
            setProgress(0, 0, true)
            priority = PRIORITY_LOW
        }.build()
        nm.notify(NOTIFICATION_ID_BACKGROUND, notification)
    }

    fun onServiceDestroyed() {
        nm.cancel(NOTIFICATION_ID_BACKGROUND)
        // Cancel left-over notifications that are still ongoing.
        //
        // We have seen a race condition where the service was taken down at the same time
        // as BackupObserver#backupFinished() was called, early enough to miss the cancel.
        //
        // This won't bring back the expected finish notification in this case,
        // but at least we don't leave stuck notifications laying around.
        nm.activeNotifications.forEach { notification ->
            // only consider ongoing notifications in our ID space (storage backup uses > 1000)
            if (notification.isOngoing && notification.id < 1000) {
                Log.w(TAG, "Needed to clean up notification with ID ${notification.id}")
                nm.cancel(notification.id)
            }
        }
    }

    fun onBackupFinished(success: Boolean, numBackedUp: Int?) {
        val titleRes =
            if (success) R.string.notification_success_title else R.string.notification_failed_title
        val total = expectedAppTotals?.appsTotal
        val contentText = if (numBackedUp == null || total == null) null else {
            context.getString(R.string.notification_success_text, numBackedUp, total)
        }
        val iconRes = if (success) R.drawable.ic_cloud_done else R.drawable.ic_cloud_error
        val intent = Intent(context, SettingsActivity::class.java).apply {
            if (success) action = ACTION_APP_STATUS_LIST
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, FLAG_IMMUTABLE)
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
        expectedApps = null
        expectedAppTotals = null
    }

    fun hasActiveBackupNotifications(): Boolean {
        nm.activeNotifications.forEach {
            if (it.packageName == context.packageName) {
                if (it.id == NOTIFICATION_ID_BACKGROUND) return true
                if (it.id == NOTIFICATION_ID_OBSERVER) return it.isOngoing
            }
        }
        return false
    }

    @SuppressLint("RestrictedApi")
    fun onBackupError() {
        val intent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, FLAG_IMMUTABLE)
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

    @SuppressLint("RestrictedApi")
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
        val flags = FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
        val pendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_CODE_UNINSTALL, intent, flags)
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

    @SuppressLint("RestrictedApi")
    fun onNoMainKeyError() {
        val intent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, FLAG_IMMUTABLE)
        val actionText = context.getString(R.string.notification_error_action)
        val action = Action(0, actionText, pendingIntent)
        val notification = Builder(context, CHANNEL_ID_ERROR).apply {
            setSmallIcon(R.drawable.ic_cloud_error)
            setContentTitle(context.getString(R.string.notification_error_no_main_key_title))
            setContentText(context.getString(R.string.notification_error_no_main_key_text))
            setWhen(System.currentTimeMillis())
            setOnlyAlertOnce(true)
            setAutoCancel(false)
            setOngoing(true)
            setContentIntent(pendingIntent)
            mActions = arrayListOf(action)
        }.build()
        nm.notify(NOTIFICATION_ID_NO_MAIN_KEY_ERROR, notification)
    }

    fun onNoMainKeyErrorFixed() {
        nm.cancel(NOTIFICATION_ID_NO_MAIN_KEY_ERROR)
    }

}
