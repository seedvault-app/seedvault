package com.stevesoltys.seedvault.restore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.core.net.toUri
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import org.koin.core.context.KoinContextHandler.get

internal const val ACTION_RESTORE_ERROR_UNINSTALL = "com.stevesoltys.seedvault.action.UNINSTALL"
internal const val EXTRA_PACKAGE_NAME = "com.stevesoltys.seedvault.extra.PACKAGE_NAME"
internal const val REQUEST_CODE_UNINSTALL = 4576841

class RestoreErrorBroadcastReceiver : BroadcastReceiver() {

    // using KoinComponent would crash robolectric tests :(
    private val notificationManager: BackupNotificationManager by lazy {
        get().get<BackupNotificationManager>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE_ERROR_UNINSTALL) return

        notificationManager.onRestoreErrorSeen()

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)!!

        @Suppress("DEPRECATION") // the alternative doesn't work for us
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = "package:$packageName".toUri()
            flags = FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(i)
    }

}
