package com.stevesoltys.seedvault.transport

import android.app.backup.BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
import android.app.backup.BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER
import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.app.backup.RestoreSet
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsActivity
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// If we ever change this, we should use a ComponentName like the other backup transports.
val TRANSPORT_ID: String = ConfigurableBackupTransport::class.java.name

const val DEFAULT_TRANSPORT_FLAGS = FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
const val D2D_TRANSPORT_FLAGS = DEFAULT_TRANSPORT_FLAGS or FLAG_DEVICE_TO_DEVICE_TRANSFER

private const val TRANSPORT_DIRECTORY_NAME =
    "com.stevesoltys.seedvault.transport.ConfigurableBackupTransport"
private val TAG = ConfigurableBackupTransport::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransport internal constructor(private val context: Context) :
    BackupTransport(), KoinComponent {

    private val backupCoordinator by inject<BackupCoordinator>()
    private val restoreCoordinator by inject<RestoreCoordinator>()
    private val settingsManager by inject<SettingsManager>()

    override fun transportDirName(): String {
        return TRANSPORT_DIRECTORY_NAME
    }

    /**
     * Ask the transport for the name under which it should be registered.
     * This will typically be its host service's component name, but need not be.
     */
    override fun name(): String {
        return TRANSPORT_ID
    }

    /**
     * Returns flags with additional information about the transport,
     * which is accessible to the BackupAgent.
     * This allows the agent to decide what to do based on properties of the transport.
     */
    override fun getTransportFlags(): Int {
        return if (settingsManager.d2dBackupsEnabled()) {
            D2D_TRANSPORT_FLAGS
        } else {
            DEFAULT_TRANSPORT_FLAGS
        }
    }

    /**
     * Ask the transport for an [Intent] that can be used to launch
     * a more detailed secondary data management activity.
     * For example, the configuration intent might be one for allowing the user
     * to select which account they wish to associate their backups with,
     * and the management intent might be one which presents a UI
     * for managing the data on the backend.
     *
     * In the Settings UI, the configuration intent will typically be invoked
     * when the user taps on the preferences item labeled with the current destination string,
     * and the management intent will be placed in an overflow menu labelled
     * with the management label string.
     *
     * If the transport does not supply any user-facing data management UI,
     * then it should return {@code null} from this method.
     *
     * @return An intent that can be passed to [Context.startActivity] in order to
     *         launch the transport's data-management UI.  This method will return
     *         {@code null} if the transport does not offer any user-facing data
     *         management UI.
     */
    override fun dataManagementIntent(): Intent {
        return Intent(context, SettingsActivity::class.java)
    }

    /**
     * On demand, supply a short CharSequence that can be shown to the user
     * as the label on an overflow menu item used to invoke the data management UI.
     *
     * @return A CharSequence to be used as the label for the transport's data management
     *     affordance. If the transport supplies a data management intent, this method must not
     *     return {@code null}.
     */
    override fun dataManagementIntentLabel(): CharSequence {
        return context.getString(R.string.data_management_label)
    }

    /**
     * On demand, supply a one-line string that can be shown to the user
     * that describes the current backend destination.
     * For example, a transport that can potentially associate backup data
     * with arbitrary user accounts should include the name of the currently-active account here.
     *
     * @return A string describing the destination to which the transport is currently
     *         sending data.  This method should not return null.
     */
    override fun currentDestinationString(): String {
        return context.getString(R.string.current_destination_string)
    }

    // ------------------------------------------------------------------------------------
    // General backup methods
    //

    override fun initializeDevice(): Int = runBlocking {
        backupCoordinator.initializeDevice()
    }

    override fun isAppEligibleForBackup(
        targetPackage: PackageInfo,
        isFullBackup: Boolean,
    ): Boolean {
        return backupCoordinator.isAppEligibleForBackup(targetPackage, isFullBackup)
    }

    override fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long = runBlocking {
        backupCoordinator.getBackupQuota(packageName, isFullBackup)
    }

    override fun clearBackupData(packageInfo: PackageInfo): Int = runBlocking {
        backupCoordinator.clearBackupData(packageInfo)
    }

    override fun finishBackup(): Int = runBlocking {
        backupCoordinator.finishBackup()
    }

    // ------------------------------------------------------------------------------------
    // Key/value incremental backup support
    //

    override fun requestBackupTime(): Long {
        return backupCoordinator.requestBackupTime()
    }

    override fun performBackup(
        packageInfo: PackageInfo,
        inFd: ParcelFileDescriptor,
        flags: Int,
    ): Int = runBlocking {
        backupCoordinator.performIncrementalBackup(packageInfo, inFd, flags)
    }

    override fun performBackup(
        targetPackage: PackageInfo,
        fileDescriptor: ParcelFileDescriptor,
    ): Int {
        Log.w(TAG, "Warning: Legacy performBackup() method called.")
        return performBackup(targetPackage, fileDescriptor, 0)
    }

    // ------------------------------------------------------------------------------------
    // Full backup
    //

    override fun requestFullBackupTime(): Long {
        return backupCoordinator.requestFullBackupTime()
    }

    override fun checkFullBackupSize(size: Long): Int {
        return backupCoordinator.checkFullBackupSize(size)
    }

    override fun performFullBackup(
        targetPackage: PackageInfo,
        socket: ParcelFileDescriptor,
        flags: Int,
    ): Int = runBlocking {
        backupCoordinator.performFullBackup(targetPackage, socket, flags)
    }

    override fun performFullBackup(
        targetPackage: PackageInfo,
        fileDescriptor: ParcelFileDescriptor,
    ): Int = runBlocking {
        Log.w(TAG, "Warning: Legacy performFullBackup() method called.")
        backupCoordinator.performFullBackup(targetPackage, fileDescriptor, 0)
    }

    override fun sendBackupData(numBytes: Int): Int = runBlocking {
        backupCoordinator.sendBackupData(numBytes)
    }

    override fun cancelFullBackup() = runBlocking {
        backupCoordinator.cancelFullBackup()
    }

    // ------------------------------------------------------------------------------------
    // Restore
    //

    override fun getAvailableRestoreSets(): Array<RestoreSet>? = runBlocking {
        restoreCoordinator.getAvailableRestoreSets()
    }

    override fun getCurrentRestoreSet(): Long {
        return restoreCoordinator.getCurrentRestoreSet()
    }

    override fun startRestore(token: Long, packages: Array<PackageInfo>): Int = runBlocking {
        restoreCoordinator.startRestore(token, packages)
    }

    override fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int = runBlocking {
        restoreCoordinator.getNextFullRestoreDataChunk(socket)
    }

    override fun nextRestorePackage(): RestoreDescription? = runBlocking {
        restoreCoordinator.nextRestorePackage()
    }

    override fun getRestoreData(outputFileDescriptor: ParcelFileDescriptor): Int = runBlocking {
        restoreCoordinator.getRestoreData(outputFileDescriptor)
    }

    override fun abortFullRestore(): Int {
        return restoreCoordinator.abortFullRestore()
    }

    override fun finishRestore() {
        restoreCoordinator.finishRestore()
    }

}
