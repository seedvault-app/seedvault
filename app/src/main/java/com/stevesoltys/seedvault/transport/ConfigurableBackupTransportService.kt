package com.stevesoltys.seedvault.transport

import android.app.Service
import android.app.backup.IBackupManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.transport.backup.BackupRequester
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext.get

private val TAG = ConfigurableBackupTransportService::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransportService : Service(), KoinComponent {

    private var transport: ConfigurableBackupTransport? = null

    private val keyManager: KeyManager by inject()
    private val backupManager: IBackupManager by inject()
    private val notificationManager: BackupNotificationManager by inject()

    override fun onCreate() {
        super.onCreate()
        transport = ConfigurableBackupTransport(applicationContext)
        Log.d(TAG, "Service created.")
    }

    override fun onBind(intent: Intent): IBinder? {
        // refuse to work until we have the main key
        val noMainKey = keyManager.hasBackupKey() && !keyManager.hasMainKey()
        if (noMainKey && backupManager.currentTransport == TRANSPORT_ID) {
            notificationManager.onNoMainKeyError()
            backupManager.isBackupEnabled = false
        }
        val transport = this.transport ?: throw IllegalStateException("no transport in onBind()")
        return transport.binder.apply {
            Log.d(TAG, "Transport bound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.onServiceDestroyed()
        transport = null
        Log.d(TAG, "Service destroyed.")
    }

}

/**
 * Requests the system to initiate a backup.
 *
 * @return true iff backups was requested successfully (backup itself can still fail).
 */
@WorkerThread
fun requestBackup(context: Context): Boolean {
    val backupManager: IBackupManager = get().get()
    return if (backupManager.isBackupEnabled) {
        val packageService: PackageService = get().get()

        Log.d(TAG, "Backup is enabled, request backup...")
        val backupRequester = BackupRequester(context, backupManager, packageService)
        return backupRequester.requestBackup()
    } else {
        Log.i(TAG, "Backup is not enabled")
        true // this counts as success
    }
}
