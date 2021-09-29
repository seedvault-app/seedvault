package de.grobox.storagebackuptester

import android.content.Context
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import org.calyxos.backup.storage.backup.BackupService
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import org.calyxos.backup.storage.restore.NotificationRestoreObserver
import org.calyxos.backup.storage.restore.RestoreService
import java.util.concurrent.TimeUnit.HOURS

// debug with:
// adb shell dumpsys jobscheduler | grep -B 4 -A 24 "Service: de.grobox.storagebackuptester/.DemoBackupJobService"

class DemoBackupJobService : BackupJobService(DemoBackupService::class.java) {
    companion object {
        fun scheduleJob(context: Context) {
            scheduleJob(
                context = context,
                jobServiceClass = DemoBackupJobService::class.java,
//                periodMillis = JobInfo.getMinPeriodMillis(), // for testing
                periodMillis = HOURS.toMillis(12), // less than 15min won't work
                deviceIdle = false,
                charging = false,
            )
        }
    }
}

class DemoBackupService : BackupService() {
    // use lazy delegate because context isn't available during construction time
    override val storageBackup: StorageBackup by lazy { (application as App).storageBackup }
    override val backupObserver: BackupObserver by lazy {
        NotificationBackupObserver(applicationContext)
    }
}

class DemoRestoreService : RestoreService() {
    // use lazy delegate because context isn't available during construction time
    override val storageBackup: StorageBackup by lazy { (application as App).storageBackup }
    override val restoreObserver: RestoreObserver by lazy {
        NotificationRestoreObserver(applicationContext)
    }
}
