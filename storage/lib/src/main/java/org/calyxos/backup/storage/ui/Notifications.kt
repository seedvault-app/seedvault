/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui

import android.app.ActivityOptions
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
import android.app.Notification
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.getActivity
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.text.format.Formatter.formatShortFileSize
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationCompat.PRIORITY_DEFAULT
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.ui.check.ACTION_FINISHED
import org.calyxos.backup.storage.ui.check.ACTION_SHOW

private const val CHANNEL_ID_BACKUP = "seedvault.storage.backup"
private const val CHANNEL_ID_RESTORE = "seedvault.storage.restore"
private const val CHANNEL_ID_CHECK = "seedvault.storage.check"
internal const val NOTIFICATION_ID_BACKUP = 1000
internal const val NOTIFICATION_ID_RESTORE = 1001
internal const val NOTIFICATION_ID_RESTORE_COMPLETE = 1002
internal const val NOTIFICATION_ID_CHECK = 1003
internal const val NOTIFICATION_ID_CHECK_COMPLETE = 1004

internal class Notifications(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(createBackupChannel())
        createNotificationChannel(createRestoreChannel())
        createNotificationChannel(createCheckChannel())
    }

    companion object {
        fun onCheckCompleteNotificationSeen(nm: NotificationManager) {
            nm.cancel(NOTIFICATION_ID_CHECK_COMPLETE)
        }
    }

    private fun createBackupChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_backup_title)
        return NotificationChannel(CHANNEL_ID_BACKUP, title, IMPORTANCE_LOW).apply {
            enableVibration(false)
        }
    }

    private fun createRestoreChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_restore_title)
        return NotificationChannel(CHANNEL_ID_RESTORE, title, IMPORTANCE_LOW).apply {
            enableVibration(false)
        }
    }

    private fun createCheckChannel(): NotificationChannel {
        val title = context.getString(R.string.notification_check_channel_title)
        return NotificationChannel(CHANNEL_ID_CHECK, title, IMPORTANCE_LOW).apply {
            enableVibration(false)
        }
    }

    internal fun getBackupNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) = getNotification(
        channel = CHANNEL_ID_BACKUP,
        icon = R.drawable.ic_cloud_upload,
        title = context.getString(R.string.notification_backup_title),
        infoText = context.getString(textRes),
        transferred = transferred,
        expected = expected
    ).build()

    internal fun updateBackupNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) {
        val notification = getBackupNotification(textRes, transferred, expected)
        nm.notify(NOTIFICATION_ID_BACKUP, notification)
    }

    internal fun cancelBackupNotification() {
        nm.cancel(NOTIFICATION_ID_BACKUP)
    }

    internal fun showPruneNotification() {
        val notification = getPruneNotification(R.string.notification_prune)
        nm.notify(NOTIFICATION_ID_BACKUP, notification)
    }

    internal fun getPruneNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) = getNotification(
        channel = CHANNEL_ID_BACKUP,
        icon = R.drawable.ic_auto_delete,
        title = context.getString(R.string.notification_backup_title),
        infoText = context.getString(textRes),
        transferred = transferred,
        expected = expected
    ).build()

    internal fun updatePruneNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) {
        val notification = getPruneNotification(textRes, transferred, expected)
        nm.notify(NOTIFICATION_ID_BACKUP, notification)
    }

    internal fun cancelPruneNotification() {
        nm.cancel(NOTIFICATION_ID_BACKUP)
    }

    internal fun getRestoreNotification(restored: Int = 0, expected: Int = 0): Notification {
        val info = if (expected > 0) {
            context.getString(R.string.notification_restore_info, restored, expected)
        } else null
        return getNotification(
            channel = CHANNEL_ID_RESTORE,
            icon = R.drawable.ic_cloud_restore,
            title = context.getString(R.string.notification_restore_title),
            infoText = info,
            transferred = restored,
            expected = expected
        ).build()
    }

    internal fun updateRestoreNotification(restored: Int, expected: Int) {
        val notification = getRestoreNotification(restored, expected)
        nm.notify(NOTIFICATION_ID_RESTORE, notification)
    }

    internal fun showRestoreCompleteNotification(
        restored: Int,
        duplicates: Int,
        errors: Int,
        total: Int,
        intent: PendingIntent?,
    ) {
        val title = context.getString(R.string.notification_restore_complete_title, restored, total)
        val msg = StringBuilder().apply {
            if (duplicates > 0) {
                append(context.getString(R.string.notification_restore_complete_dups, duplicates))
            }
            if (errors > 0) {
                if (duplicates > 0) append("\n")
                append(context.getString(R.string.notification_restore_complete_errors, errors))
            }
        }.toString().ifEmpty { null }
        val notification = Builder(context, CHANNEL_ID_BACKUP).apply {
            setSmallIcon(R.drawable.ic_cloud_done)
            setContentTitle(title)
            setContentText(msg)
            setOngoing(false)
            setShowWhen(true)
            setAutoCancel(true)
            setContentIntent(intent)
            setWhen(System.currentTimeMillis())
            priority = PRIORITY_DEFAULT
        }.build()
        // use a new notification, so it can stick around after the foreground service stopped
        nm.cancel(NOTIFICATION_ID_RESTORE)
        nm.notify(NOTIFICATION_ID_RESTORE_COMPLETE, notification)
    }

    fun getCheckNotification(
        info: String? = null,
        transferred: Int = 0,
        expected: Int = 0,
    ): Builder = getNotification(
        channel = CHANNEL_ID_CHECK,
        icon = R.drawable.ic_cloud_search,
        title = context.getString(R.string.notification_check_title),
        infoText = info,
        transferred = transferred,
        expected = expected,
    )

    fun showCheckStartedNotification() {
        val text = context.getString(R.string.notification_check_text)
        val notification = getCheckNotification(text).build()
        nm.notify(NOTIFICATION_ID_CHECK, notification)
    }

    fun showCheckNotification(speed: Long, thousandth: Int) {
        val text = "${formatShortFileSize(context, speed)}/s"
        val notification = getCheckNotification(text, thousandth, 1000).build()
        nm.notify(NOTIFICATION_ID_CHECK, notification)
    }

    fun onCheckComplete(reportClass: Class<*>, size: Long, speed: Long) {
        val text = context.getString(
            R.string.notification_check_finished_text,
            formatShortFileSize(context, size),
            "${formatShortFileSize(context, speed)}/s",
        )
        val notification = getOnCheckFinishedBuilder(reportClass)
            .setContentTitle(context.getString(R.string.notification_check_finished_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cloud_done)
            .build()
        nm.cancel(NOTIFICATION_ID_CHECK)
        nm.notify(NOTIFICATION_ID_CHECK_COMPLETE, notification)
    }

    fun onCheckFinishedWithError(reportClass: Class<*>, size: Long, speed: Long) {
        val text = context.getString(
            R.string.notification_check_error_text,
            formatShortFileSize(context, size),
            "${formatShortFileSize(context, speed)}/s",
        )
        val notification = getOnCheckFinishedBuilder(reportClass)
            .setContentTitle(context.getString(R.string.notification_check_error_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cloud_error)
            .build()
        nm.cancel(NOTIFICATION_ID_CHECK)
        nm.notify(NOTIFICATION_ID_CHECK_COMPLETE, notification)
    }

    private fun getOnCheckFinishedBuilder(reportClass: Class<*>): Builder {
        // the background activity launch (BAL) gets restricted for setDeleteIntent()
        // if we don't use these special ActivityOptions, may cause issues in future SDKs
        val options = ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            ).toBundle()
        val cIntent = Intent(context, reportClass).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            setAction(ACTION_SHOW)
        }
        val dIntent = Intent(context, reportClass).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            setAction(ACTION_FINISHED)
        }
        val contentIntent = getActivity(context, 1, cIntent, FLAG_IMMUTABLE, options)
        val deleteIntent = getActivity(context, 2, dIntent, FLAG_IMMUTABLE, options)
        val actionTitle = context.getString(R.string.notification_check_action)
        val action = Action.Builder(null, actionTitle, contentIntent).build()
        return getCheckNotification()
            .setProgress(0, 0, false)
            .setContentIntent(contentIntent)
            .addAction(action)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(true)
    }

    private fun getNotification(
        channel: String,
        @DrawableRes icon: Int,
        title: CharSequence,
        infoText: CharSequence?,
        transferred: Int = 0,
        expected: Int = 0,
    ) = Builder(context, channel).apply {
        setSmallIcon(icon)
        setContentTitle(title)
        setContentText(infoText)
        setOngoing(true)
        setShowWhen(false)
        setWhen(System.currentTimeMillis())
        setProgress(expected, transferred, expected == 0)
        setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
    }
}
