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
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.systemData
import org.calyxos.seedvault.core.backends.isOutOfSpace
import java.io.IOException
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
    private val backendManager: BackendManager,
    private val appBackupManager: AppBackupManager,
    private val kv: KVBackup,
    private val full: FullBackup,
    private val packageService: PackageService,
    private val metadataManager: MetadataManager,
    private val settingsManager: SettingsManager,
    private val nm: BackupNotificationManager,
) {

    private val snapshotCreator
        get() = appBackupManager.snapshotCreator ?: error("No SnapshotCreator")
    private val state = CoordinatorState(
        calledInitialize = false,
        calledClearBackupData = false,
        cancelReason = UNKNOWN_ERROR
    )
    private val launchableSystemApps by lazy {
        packageService.launchableSystemApps.map { it.activityInfo.packageName }.toSet()
    }

    // ------------------------------------------------------------------------------------
    // Transport initialization and quota
    //

    /**
     * Initialize the storage for this device, erasing all stored data.
     * The transport may send the request immediately, or may buffer it.
     * After this is called,
     * [finishBackup] will be called to ensure the request is sent and received successfully.
     *
     * If the transport returns anything other than [TRANSPORT_OK] from this method,
     * the OS will halt the current initialize operation and schedule a retry in the near future.
     * Attention: [finishBackup] will not be called in this case.
     *
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
    fun initializeDevice(): Int {
        Log.i(TAG, "Initialize Device!")
        // we don't respect the intended system behavior of erasing all stored data
        state.calledInitialize = true
        return TRANSPORT_OK
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
    suspend fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long {
        Log.i(TAG, "Get backup quota for $packageName. Is full backup: $isFullBackup.")

        if (!isFullBackup) {
            // hack for `adb shell bmgr backupnow`
            // which starts with a K/V backup calling this method, so we hook in here
            appBackupManager.ensureBackupPrepared()
        }
        val quota = settingsManager.quota
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
    fun performIncrementalBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int,
    ): Int {
        state.cancelReason = UNKNOWN_ERROR
        return kv.performBackup(packageInfo, data, flags)
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

    fun performFullBackup(
        targetPackage: PackageInfo,
        fileDescriptor: ParcelFileDescriptor,
        flags: Int,
    ): Int {
        state.cancelReason = UNKNOWN_ERROR
        return full.performFullBackup(targetPackage, fileDescriptor, flags)
    }

    /**
     * Tells the transport to read [numBytes] bytes of data from the socket file descriptor
     * provided in the [performFullBackup] call, and deliver those bytes to the datastore.
     *
     * @param numBytes The number of bytes of tarball data available to be read from the socket.
     * @return [TRANSPORT_OK] on successful processing of the data; [TRANSPORT_ERROR] to
     *    indicate a fatal error situation.  If an error is returned, the system will
     *    call finishBackup() and stop attempting backups until after a backoff and retry
     *    interval.
     */
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
        val packageInfo = full.currentPackageInfo
            ?: error("Cancelling full backup, but no current package")
        val packageName = packageInfo.packageName
        Log.i(TAG, "Cancel full backup of $packageName because of ${state.cancelReason}")
        // don't bother with remembering state for boring system apps that have no data
        val ignoreApp = state.cancelReason == NO_DATA &&
            packageInfo.isSystemApp() &&
            packageName !in systemData.keys && // don't ignore our special system apps
            packageName !in launchableSystemApps // don't ignore launchable system apps
        if (!ignoreApp) onPackageBackupError(packageInfo, BackupType.FULL)
        full.cancelFullBackup()
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
    fun clearBackupData(packageInfo: PackageInfo): Int {
        Log.i(TAG, "Ignoring clear backup data of ${packageInfo.packageName}.")
        // we don't clear backup data anymore, we have snapshots and those old ones stay valid
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
        kv.hasState -> {
            check(!full.hasState) {
                "K/V backup has state, but full backup has dangling state as well"
            }
            // getCurrentPackage() not-null because we have state, call before finishing
            val packageInfo = kv.currentPackageInfo!!
            val packageName = packageInfo.packageName
            try {
                // tell K/V backup to finish
                val backupData = kv.finishBackup()
                snapshotCreator.onPackageBackedUp(packageInfo, BackupType.KV, backupData)
                TRANSPORT_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing K/V backup for $packageName", e)
                if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
                onPackageBackupError(packageInfo, BackupType.KV)
                TRANSPORT_PACKAGE_REJECTED
            }
        }
        full.hasState -> {
            check(!kv.hasState) {
                "Full backup has state, but K/V backup has dangling state as well"
            }
            // getCurrentPackage() not-null because we have state
            val packageInfo = full.currentPackageInfo!!
            val packageName = packageInfo.packageName
            // tell full backup to finish
            try {
                val backupData = full.finishBackup()
                snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, backupData)
                TRANSPORT_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error calling onPackageBackedUp for $packageName", e)
                if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
                onPackageBackupError(packageInfo, BackupType.FULL)
                TRANSPORT_PACKAGE_REJECTED
            }
        }
        state.expectFinish -> {
            state.onFinish()
            TRANSPORT_OK
        }
        else -> throw IllegalStateException("Unexpected state in finishBackup()")
    }

    private fun onPackageBackupError(packageInfo: PackageInfo, type: BackupType) {
        val packageName = packageInfo.packageName
        try {
            metadataManager.onPackageBackupError(packageInfo, state.cancelReason, type)
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing metadata for $packageName", e)
        }
    }

    private fun getBackupBackoff(): Long {
        val longBackoff = DAYS.toMillis(30)

        // back off if there's no storage set
        val storage = backendManager.backendProperties ?: return longBackoff
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
}
