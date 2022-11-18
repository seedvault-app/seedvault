package com.stevesoltys.seedvault.ui.notification

import android.app.backup.BackupProgress
import android.app.backup.IBackupObserver
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import android.util.Log.INFO
import android.util.Log.isLoggable
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.transport.backup.ExpectedAppTotals
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val TAG = NotificationBackupObserver::class.java.simpleName

internal class NotificationBackupObserver(
    private val context: Context,
    private val expectedPackages: Int,
    appTotals: ExpectedAppTotals,
) : IBackupObserver.Stub(), KoinComponent {

    private val nm: BackupNotificationManager by inject()
    private val metadataManager: MetadataManager by inject()
    private var currentPackage: String? = null
    private var numPackages: Int = 0

    init {
        // Inform the notification manager that a backup has started
        // and inform about the expected numbers, so it can compute a total.
        nm.onBackupStarted(expectedPackages, appTotals)
    }

    /**
     * This method could be called several times for packages with full data backup.
     * It will tell how much of backup data is already saved and how much is expected.
     *
     * Note that this will not be called for [MAGIC_PACKAGE_MANAGER]
     * which is usually the first package to get backed up.
     *
     * @param currentBackupPackage The name of the package that now being backed up.
     * @param backupProgress Current progress of backup for the package.
     */
    override fun onUpdate(currentBackupPackage: String?, backupProgress: BackupProgress) {
        showProgressNotification(currentBackupPackage)
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
    override fun onResult(target: String?, status: Int) {
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Completed. Target: $target, status: $status")
        }
        // often [onResult] gets called right away without any [onUpdate] call
        showProgressNotification(target)
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
            Log.i(TAG, "Backup finished $numPackages/$expectedPackages. Status: $status")
        }
        val success = status == 0
        val numBackedUp = if (success) metadataManager.getPackagesNumBackedUp() else null
        nm.onBackupFinished(success, numBackedUp)
    }

    private fun showProgressNotification(packageName: String?) {
        if (packageName == null || currentPackage == packageName) return

        if (isLoggable(TAG, INFO)) {
            "Showing progress notification for $currentPackage $numPackages/$expectedPackages".let {
                Log.i(TAG, it)
            }
        }
        currentPackage = packageName
        val app = getAppName(packageName)
        numPackages += 1
        nm.onBackupUpdate(app, numPackages)
    }

    private fun getAppName(packageId: String): CharSequence = getAppName(context, packageId)

}

fun getAppName(context: Context, packageId: String): CharSequence {
    if (packageId == MAGIC_PACKAGE_MANAGER || packageId.startsWith("@")) {
        return context.getString(R.string.restore_magic_package)
    }
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageId, 0)
        context.packageManager.getApplicationLabel(appInfo)
    } catch (e: NameNotFoundException) {
        packageId
    }
}
