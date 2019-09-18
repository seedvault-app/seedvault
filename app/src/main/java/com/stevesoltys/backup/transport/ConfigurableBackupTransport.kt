package com.stevesoltys.backup.transport

import android.app.backup.BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.app.backup.RestoreSet
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.backup.settings.SettingsActivity

val TRANSPORT_ID: String = ConfigurableBackupTransport::class.java.name

private const val TRANSPORT_DIRECTORY_NAME = "com.stevesoltys.backup.transport.ConfigurableBackupTransport"
private val TAG = ConfigurableBackupTransport::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransport internal constructor(private val context: Context) : BackupTransport() {

    private val pluginManager = PluginManager(context)
    private val backupCoordinator = pluginManager.backupCoordinator
    private val restoreCoordinator = pluginManager.restoreCoordinator

    override fun transportDirName(): String {
        return TRANSPORT_DIRECTORY_NAME
    }

    override fun name(): String {
        return TRANSPORT_ID
    }

    override fun getTransportFlags(): Int {
        return FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
    }

    override fun dataManagementIntent(): Intent {
        return Intent(context, SettingsActivity::class.java)
    }

    override fun dataManagementLabel(): String {
        return "Please file a bug if you see this! 1"
    }

    override fun currentDestinationString(): String {
        return "Please file a bug if you see this! 2"
    }

    // ------------------------------------------------------------------------------------
    // General backup methods
    //

    override fun initializeDevice(): Int {
        return backupCoordinator.initializeDevice()
    }

    override fun isAppEligibleForBackup(targetPackage: PackageInfo, isFullBackup: Boolean): Boolean {
        return backupCoordinator.isAppEligibleForBackup(targetPackage, isFullBackup)
    }

    override fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long {
        return backupCoordinator.getBackupQuota(packageName, isFullBackup)
    }

    override fun clearBackupData(packageInfo: PackageInfo): Int {
        return backupCoordinator.clearBackupData(packageInfo)
    }

    override fun finishBackup(): Int {
        return backupCoordinator.finishBackup()
    }

    // ------------------------------------------------------------------------------------
    // Key/value incremental backup support
    //

    override fun requestBackupTime(): Long {
        return backupCoordinator.requestBackupTime()
    }

    override fun performBackup(packageInfo: PackageInfo, inFd: ParcelFileDescriptor, flags: Int): Int {
        return backupCoordinator.performIncrementalBackup(packageInfo, inFd, flags)
    }

    override fun performBackup(targetPackage: PackageInfo, fileDescriptor: ParcelFileDescriptor): Int {
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

    override fun performFullBackup(targetPackage: PackageInfo, socket: ParcelFileDescriptor, flags: Int): Int {
        return backupCoordinator.performFullBackup(targetPackage, socket, flags)
    }

    override fun performFullBackup(targetPackage: PackageInfo, fileDescriptor: ParcelFileDescriptor): Int {
        Log.w(TAG, "Warning: Legacy performFullBackup() method called.")
        return backupCoordinator.performFullBackup(targetPackage, fileDescriptor, 0)
    }

    override fun sendBackupData(numBytes: Int): Int {
        return backupCoordinator.sendBackupData(numBytes)
    }

    override fun cancelFullBackup() {
        backupCoordinator.cancelFullBackup()
    }

    // ------------------------------------------------------------------------------------
    // Restore
    //

    override fun getAvailableRestoreSets(): Array<RestoreSet>? {
        return restoreCoordinator.getAvailableRestoreSets()
    }

    override fun getCurrentRestoreSet(): Long {
        return restoreCoordinator.getCurrentRestoreSet()
    }

    override fun startRestore(token: Long, packages: Array<PackageInfo>): Int {
        return restoreCoordinator.startRestore(token, packages)
    }

    override fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int {
        return restoreCoordinator.getNextFullRestoreDataChunk(socket)
    }

    override fun nextRestorePackage(): RestoreDescription? {
        return restoreCoordinator.nextRestorePackage()
    }

    override fun getRestoreData(outputFileDescriptor: ParcelFileDescriptor): Int {
        return restoreCoordinator.getRestoreData(outputFileDescriptor)
    }

    override fun abortFullRestore(): Int {
        return restoreCoordinator.abortFullRestore()
    }

    override fun finishRestore() {
        restoreCoordinator.finishRestore()
    }

}
