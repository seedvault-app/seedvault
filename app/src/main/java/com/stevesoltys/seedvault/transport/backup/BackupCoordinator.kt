/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.FLAG_DATA_NOT_CHANGED
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_NOT_INITIALIZED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.app.backup.RestoreSet
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.plugins.isOutOfSpace
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit.DAYS
import java.util.concurrent.TimeUnit.HOURS

private val TAG = BackupCoordinator::class.java.simpleName

private class CoordinatorState(
    var calledInitialize: Boolean,
    var calledClearBackupData: Boolean,
    var cancelReason: PackageState,
) {
    val expectFinish: Boolean
        get() = calledInitialize || calledClearBackupData

    fun onFinish() {
        calledInitialize = false
        calledClearBackupData = false
        cancelReason = UNKNOWN_ERROR
    }
}

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
@WorkerThread
internal class BackupCoordinator(
    private val context: Context,
    private val pluginManager: StoragePluginManager,
    private val kv: KVBackup,
    private val full: FullBackup,
    private val clock: Clock,
    private val packageService: PackageService,
    private val metadataManager: MetadataManager,
    private val settingsManager: SettingsManager,
    private val nm: BackupNotificationManager,
) {

    private val plugin get() = pluginManager.appPlugin
    private val state = CoordinatorState(
        calledInitialize = false,
        calledClearBackupData = false,
        cancelReason = UNKNOWN_ERROR
    )

    // ------------------------------------------------------------------------------------
    // Transport initialization and quota
    //

    /**
     * Starts a new [RestoreSet] with a new token (the current unix epoch in milliseconds).
     * Call this at least once before calling [initializeDevice]
     * which must be called after this method to properly initialize the backup transport.
     *
     * @return the token of the new [RestoreSet].
     */
    @Throws(IOException::class)
    private suspend fun startNewRestoreSet() {
        val token = clock.time()
        Log.i(TAG, "Starting new RestoreSet with token $token...")
        settingsManager.setNewToken(token)
        plugin.startNewRestoreSet(token)
        Log.d(TAG, "Resetting backup metadata...")
        metadataManager.onDeviceInitialization(token)
    }

    /**
     * Initialize the storage for this device, erasing all stored data.
     * The transport may send the request immediately, or may buffer it.
     * After this is called,
     * [finishBackup] will be called to ensure the request is sent and received successfully.
     *
     * If the transport returns anything other than [TRANSPORT_OK] from this method,
     * the OS will halt the current initialize operation and schedule a retry in the near future.
     * Even if the transport is in a state
     * such that attempting to "initialize" the backend storage is meaningless -
     * for example, if there is no current live data-set at all,
     * or there is no authenticated account under which to store the data remotely -
     * the transport should return [TRANSPORT_OK] here
     * and treat the [initializeDevice] / [finishBackup] pair as a graceful no-op.
     *
     * @return One of [TRANSPORT_OK] (OK so far) or
     * [TRANSPORT_ERROR] (to retry following network error or other failure).
     */
    suspend fun initializeDevice(): Int = try {
        // we don't respect the intended system behavior here by always starting a new [RestoreSet]
        // instead of simply deleting the current one
        startNewRestoreSet()
        Log.i(TAG, "Initialize Device!")
        plugin.initializeDevice()
        // [finishBackup] will only be called when we return [TRANSPORT_OK] here
        // so we remember that we initialized successfully
        state.calledInitialize = true
        TRANSPORT_OK
    } catch (e: Exception) {
        Log.e(TAG, "Error initializing device", e)
        // Show error notification if we needed init or were ready for backups
        if (metadataManager.requiresInit || pluginManager.canDoBackupNow()) nm.onBackupError()
        TRANSPORT_ERROR
    }

    fun isAppEligibleForBackup(
        targetPackage: PackageInfo,
        @Suppress("UNUSED_PARAMETER") isFullBackup: Boolean,
    ): Boolean {
        val packageName = targetPackage.packageName
        val shouldInclude = packageService.shouldIncludeAppInBackup(packageName)
        if (!shouldInclude) Log.i(TAG, "Excluding $packageName from backup.")
        return shouldInclude
    }

    /**
     * Ask the transport about current quota for backup size of the package.
     *
     * @param packageName ID of package to provide the quota.
     * @param isFullBackup If set, transport should return limit for full data backup,
     *                      otherwise for key-value backup.
     * @return Current limit on backup size in bytes.
     */
    fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long {
        // report back quota
        Log.i(TAG, "Get backup quota for $packageName. Is full backup: $isFullBackup.")
        val quota = if (isFullBackup) full.getQuota() else kv.getQuota()
        Log.i(TAG, "Reported quota of $quota bytes.")
        return quota
    }

    // ------------------------------------------------------------------------------------
    // Key/value incremental backup support
    //

    /**
     * Verify that this is a suitable time for a key/value backup pass.
     * This should return zero if a backup is reasonable right now, some positive value otherwise.
     * This method will be called outside of the [performIncrementalBackup]/[finishBackup] pair.
     *
     * If this is not a suitable time for a backup, the transport should return a backoff delay,
     * in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     */
    fun requestBackupTime(): Long = getBackupBackoff().apply {
        Log.i(TAG, "Request incremental backup time. Returned $this")
    }

    /**
     * Send one application's key/value data update to the backup destination.
     * The transport may send the data immediately, or may buffer it.
     * If this method returns [TRANSPORT_OK], [finishBackup] will then be called
     * to ensure the data is sent and recorded successfully.
     *
     * If the backup data is a diff against the previous backup
     * then the flag [FLAG_INCREMENTAL] will be set.
     * Otherwise, if the data is a complete backup set,
     * then [FLAG_NON_INCREMENTAL] will be set.
     * Before P neither flag will be set regardless of whether the backup is incremental or not.
     *
     * If [FLAG_INCREMENTAL] is set and the transport does not have data
     * for this package in its storage backend then it cannot apply the incremental diff.
     * Thus it should return [TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED]
     * to indicate that backup manager should delete its state
     * and retry the package as a non-incremental backup.
     *
     * Note that if an app (e.g. com.whatsapp) has no data to backup,
     * this method will NOT even be called for the app.
     *
     * @param packageInfo The identity of the application whose data is being backed up.
     *   This specifically includes the signature list for the package.
     * @param data Descriptor of file with data that resulted from invoking the application's
     *   BackupService.doBackup() method.  This may be a pipe rather than a file on
     *   persistent media, so it may not be seekable.
     * @param flags a combination of [FLAG_USER_INITIATED], [FLAG_NON_INCREMENTAL],
     *   [FLAG_INCREMENTAL], [FLAG_DATA_NOT_CHANGED], or 0.
     * @return one of [TRANSPORT_OK] (OK so far),
     *  [TRANSPORT_PACKAGE_REJECTED] (to suppress backup of this package, but let others proceed),
     *  [TRANSPORT_ERROR] (on network error or other failure),
     *  [TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED] (if the transport cannot accept
     *  an incremental backup for this package), or
     *  [TRANSPORT_NOT_INITIALIZED] (if the backend dataset has become lost due to
     *  inactivity purge or some other reason and needs re-initializing)
     */
    suspend fun performIncrementalBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int,
    ): Int {
        state.cancelReason = UNKNOWN_ERROR
        if (metadataManager.requiresInit) {
            Log.w(TAG, "Metadata requires re-init!")
            // Tell the system that we are not initialized, it will initialize us afterwards.
            // This will start a new restore set to upgrade from legacy format
            // by starting a clean backup with all files using the new version.
            //
            // This causes a backup error, but things should go back to normal afterwards.
            return TRANSPORT_NOT_INITIALIZED
        }
        val token = settingsManager.getToken() ?: error("no token in performFullBackup")
        val salt = metadataManager.salt
        return kv.performBackup(packageInfo, data, flags, token, salt)
    }

    // ------------------------------------------------------------------------------------
    // Full backup
    //

    /**
     * Verify that this is a suitable time for a full-data backup pass.
     * This should return zero if a backup is reasonable right now, some positive value otherwise.
     * This method will be called outside of the [performFullBackup]/[finishBackup] pair.
     *
     * If this is not a suitable time for a backup, the transport should return a backoff delay,
     * in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     *
     * @see [requestBackupTime]
     */
    fun requestFullBackupTime(): Long = getBackupBackoff().apply {
        Log.i(TAG, "Request full backup time. Returned $this")
    }

    fun checkFullBackupSize(size: Long): Int {
        val result = full.checkFullBackupSize(size)
        if (result == TRANSPORT_PACKAGE_REJECTED) state.cancelReason = NO_DATA
        else if (result == TRANSPORT_QUOTA_EXCEEDED) state.cancelReason = QUOTA_EXCEEDED
        return result
    }

    suspend fun performFullBackup(
        targetPackage: PackageInfo,
        fileDescriptor: ParcelFileDescriptor,
        flags: Int,
    ): Int {
        state.cancelReason = UNKNOWN_ERROR
        val token = settingsManager.getToken() ?: error("no token in performFullBackup")
        val salt = metadataManager.salt
        return full.performFullBackup(targetPackage, fileDescriptor, flags, token, salt)
    }

    suspend fun sendBackupData(numBytes: Int) = full.sendBackupData(numBytes)

    /**
     * Tells the transport to cancel the currently-ongoing full backup operation.
     * This will happen between [performFullBackup] and [finishBackup]
     * if the OS needs to abort the backup operation for any reason,
     * such as a crash in the application undergoing backup.
     *
     * When it receives this call,
     * the transport should discard any partial archive that it has stored so far.
     * If possible it should also roll back to the previous known-good archive in its data store.
     *
     * If the transport receives this callback, it will *not* receive a call to [finishBackup].
     * It needs to tear down any ongoing backup state here.
     */
    suspend fun cancelFullBackup() {
        val packageInfo = full.getCurrentPackage()
            ?: throw AssertionError("Cancelling full backup, but no current package")
        Log.i(
            TAG, "Cancel full backup of ${packageInfo.packageName}" +
                " because of ${state.cancelReason}"
        )
        // don't bother with system apps that have no data
        val ignoreApp = state.cancelReason == NO_DATA && packageInfo.isSystemApp()
        if (!ignoreApp) onPackageBackupError(packageInfo, BackupType.FULL)
        val token = settingsManager.getToken() ?: error("no token in cancelFullBackup")
        val salt = metadataManager.salt
        full.cancelFullBackup(token, salt, ignoreApp)
    }

    // Clear and Finish

    /**
     * Erase the given application's data from the backup destination.
     * This clears out the given package's data from the current backup set,
     * making it as though the app had never yet been backed up.
     * After this is called, [finishBackup] must be called
     * to ensure that the operation is recorded successfully.
     *
     * @return the same error codes as [performFullBackup].
     */
    suspend fun clearBackupData(packageInfo: PackageInfo): Int {
        val packageName = packageInfo.packageName
        Log.i(TAG, "Clear Backup Data of $packageName.")
        val token = settingsManager.getToken() ?: error("no token in clearBackupData")
        val salt = metadataManager.salt
        try {
            kv.clearBackupData(packageInfo, token, salt)
        } catch (e: IOException) {
            Log.w(TAG, "Error clearing K/V backup data for $packageName", e)
            return TRANSPORT_ERROR
        }
        try {
            full.clearBackupData(packageInfo, token, salt)
        } catch (e: IOException) {
            Log.w(TAG, "Error clearing full backup data for $packageName", e)
            return TRANSPORT_ERROR
        }
        state.calledClearBackupData = true
        return TRANSPORT_OK
    }

    /**
     * Finish sending application data to the backup destination. This must be called
     * after [performIncrementalBackup], [performFullBackup], or [clearBackupData]
     * to ensure that all data is sent and the operation properly finalized.
     * Only when this method returns true can a backup be assumed to have succeeded.
     *
     * @return the same error codes as [performIncrementalBackup] or [performFullBackup].
     */
    suspend fun finishBackup(): Int = when {
        kv.hasState() -> {
            check(!full.hasState()) {
                "K/V backup has state, but full backup has dangling state as well"
            }
            // getCurrentPackage() not-null because we have state, call before finishing
            val packageInfo = kv.getCurrentPackage()!!
            val packageName = packageInfo.packageName
            val size = kv.getCurrentSize()
            // tell K/V backup to finish
            var result = kv.finishBackup()
            if (result == TRANSPORT_OK) {
                val isNormalBackup = packageName != MAGIC_PACKAGE_MANAGER
                // call onPackageBackedUp for @pm@ only if we can do backups right now
                if (isNormalBackup || pluginManager.canDoBackupNow()) {
                    try {
                        onPackageBackedUp(packageInfo, BackupType.KV, size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error calling onPackageBackedUp for $packageName", e)
                        if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
                        result = TRANSPORT_PACKAGE_REJECTED
                    }
                }
            }
            result
        }
        full.hasState() -> {
            check(!kv.hasState()) {
                "Full backup has state, but K/V backup has dangling state as well"
            }
            // getCurrentPackage() not-null because we have state
            val packageInfo = full.getCurrentPackage()!!
            val packageName = packageInfo.packageName
            val size = full.getCurrentSize()
            // tell full backup to finish
            var result = full.finishBackup()
            try {
                onPackageBackedUp(packageInfo, BackupType.FULL, size)
            } catch (e: Exception) {
                Log.e(TAG, "Error calling onPackageBackedUp for $packageName", e)
                if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
                result = TRANSPORT_PACKAGE_REJECTED
            }
            result
        }
        state.expectFinish -> {
            state.onFinish()
            TRANSPORT_OK
        }
        else -> throw IllegalStateException("Unexpected state in finishBackup()")
    }

    private suspend fun onPackageBackedUp(packageInfo: PackageInfo, type: BackupType, size: Long?) {
        plugin.getMetadataOutputStream().use {
            metadataManager.onPackageBackedUp(packageInfo, type, size, it)
        }
    }

    private suspend fun onPackageBackupError(packageInfo: PackageInfo, type: BackupType) {
        val packageName = packageInfo.packageName
        try {
            plugin.getMetadataOutputStream().use {
                metadataManager.onPackageBackupError(packageInfo, state.cancelReason, it, type)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing metadata for $packageName", e)
        }
    }

    private fun getBackupBackoff(): Long {
        val longBackoff = DAYS.toMillis(30)

        // back off if there's no storage set
        val storage = pluginManager.storageProperties ?: return longBackoff
        return when {
            // back off if storage is removable and not available right now
            storage.isUnavailableUsb(context) -> longBackoff
            // back off if storage is on network, but we have no access
            storage.isUnavailableNetwork(
                context = context,
                allowMetered = settingsManager.useMeteredNetwork,
            ) -> HOURS.toMillis(1)
            // otherwise no back off
            else -> 0L
        }
    }

    private suspend fun StoragePlugin<*>.getMetadataOutputStream(
        token: Long? = null,
    ): OutputStream {
        val t = token ?: settingsManager.getToken() ?: throw IOException("no current token")
        return getOutputStream(t, FILE_BACKUP_METADATA)
    }

}
