/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui

import android.app.Notification
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_DEFAULT
import org.calyxos.backup.storage.R

private const val CHANNEL_ID_BACKUP = "seedvault.storage.backup"
private const val CHANNEL_ID_RESTORE = "seedvault.storage.restore"
internal const val NOTIFICATION_ID_BACKUP = 1000
internal const val NOTIFICATION_ID_PRUNE = 1001
internal const val NOTIFICATION_ID_RESTORE = 1002
internal const val NOTIFICATION_ID_RESTORE_COMPLETE = 1003

internal class Notifications(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(createBackupChannel())
        createNotificationChannel(createRestoreChannel())
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

    internal fun getBackupNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) = getNotification(
        icon = R.drawable.ic_cloud_upload,
        title = context.getString(R.string.notification_backup_title),
        infoText = context.getString(textRes),
        transferred = transferred,
        expected = expected
    )

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

    internal fun getPruneNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) = getNotification(
        icon = R.drawable.ic_auto_delete,
        title = context.getString(R.string.notification_backup_title),
        infoText = context.getString(textRes),
        transferred = transferred,
        expected = expected
    )

    internal fun updatePruneNotification(
        @StringRes textRes: Int,
        transferred: Int = 0,
        expected: Int = 0,
    ) {
        val notification = getPruneNotification(textRes, transferred, expected)
        nm.notify(NOTIFICATION_ID_PRUNE, notification)
    }

    internal fun cancelPruneNotification() {
        nm.cancel(NOTIFICATION_ID_PRUNE)
    }

    internal fun getRestoreNotification(restored: Int = 0, expected: Int = 0): Notification {
        val info = if (expected > 0) {
            context.getString(R.string.notification_restore_info, restored, expected)
        } else null
        return getNotification(
            icon = R.drawable.ic_cloud_restore,
            title = context.getString(R.string.notification_restore_title),
            infoText = info,
            transferred = restored,
            expected = expected
        )
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_BACKUP).apply {
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

    private fun getNotification(
        @DrawableRes icon: Int,
        title: CharSequence,
        infoText: CharSequence?,
        transferred: Int = 0,
        expected: Int = 0,
    ) = Notification.Builder(context, CHANNEL_ID_BACKUP).apply {
        setSmallIcon(icon)
        setContentTitle(title)
        setContentText(infoText)
        setOngoing(true)
        setShowWhen(false)
        setWhen(System.currentTimeMillis())
        setProgress(expected, transferred, expected == 0)
        setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
    }.build()

}
