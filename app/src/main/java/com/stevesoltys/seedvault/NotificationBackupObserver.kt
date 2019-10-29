package com.stevesoltys.seedvault

import android.app.backup.BackupProgress
import android.app.backup.IBackupObserver
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Log.INFO
import android.util.Log.isLoggable

private val TAG = NotificationBackupObserver::class.java.simpleName

class NotificationBackupObserver(context: Context, private val userInitiated: Boolean) : IBackupObserver.Stub() {

    private val pm = context.packageManager
    private val nm = (context.applicationContext as Backup).notificationManager

    /**
     * This method could be called several times for packages with full data backup.
     * It will tell how much of backup data is already saved and how much is expected.
     *
     * @param currentBackupPackage The name of the package that now being backed up.
     * @param backupProgress Current progress of backup for the package.
     */
    override fun onUpdate(currentBackupPackage: String, backupProgress: BackupProgress) {
        val transferred = backupProgress.bytesTransferred.toInt()
        val expected = backupProgress.bytesExpected.toInt()
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Update. Target: $currentBackupPackage, $transferred/$expected")
        }
        val app = getAppName(currentBackupPackage)
        nm.onBackupUpdate(app, transferred, expected, userInitiated)
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
    override fun onResult(target: String, status: Int) {
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Completed. Target: $target, status: $status")
        }
        nm.onBackupResult(getAppName(target), status, userInitiated)
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
            Log.i(TAG, "Backup finished. Status: $status")
        }
        nm.onBackupFinished()
    }

    private fun getAppName(packageId: String): CharSequence = getAppName(pm, packageId)

}

fun getAppName(pm: PackageManager, packageId: String): CharSequence {
    if (packageId == MAGIC_PACKAGE_MANAGER) return packageId
    val appInfo = pm.getApplicationInfo(packageId, 0)
    return pm.getApplicationLabel(appInfo)
}
