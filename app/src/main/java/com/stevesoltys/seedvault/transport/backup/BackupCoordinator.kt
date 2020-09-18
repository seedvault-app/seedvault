package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.app.backup.RestoreSet
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import java.io.IOException
import java.util.concurrent.TimeUnit.DAYS

private val TAG = BackupCoordinator::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
@WorkerThread // entire class should always be accessed from a worker thread, so blocking is ok
@Suppress("BlockingMethodInNonBlockingContext")
internal class BackupCoordinator(
    private val context: Context,
    private val plugin: BackupPlugin,
    private val kv: KVBackup,
    private val full: FullBackup,
    private val apkBackup: ApkBackup,
    private val clock: Clock,
    private val packageService: PackageService,
    private val metadataManager: MetadataManager,
    private val settingsManager: SettingsManager,
    private val nm: BackupNotificationManager
) {

    private var calledInitialize = false
    private var calledClearBackupData = false
    private var cancelReason: PackageState = UNKNOWN_ERROR

    // ------------------------------------------------------------------------------------
    // Transport initialization and quota
    //

    /**
     * Starts a new [RestoreSet] with a new token (the current unix epoch in milliseconds).
     * Call this at least once before calling [initializeDevice]
     * which must be called after this method to properly initialize the backup transport.
     */
    @Throws(IOException::class)
    suspend fun startNewRestoreSet() {
        val token = clock.time()
        Log.i(TAG, "Starting new RestoreSet with token $token...")
        settingsManager.setNewToken(token)
        plugin.startNewRestoreSet(token)
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
        val token = settingsManager.getToken()
        if (token == null) {
            Log.i(TAG, "No RestoreSet started, initialization is no-op.")
        } else {
            Log.i(TAG, "Initialize Device!")
            plugin.initializeDevice()
            Log.d(TAG, "Resetting backup metadata for token $token...")
            plugin.getMetadataOutputStream().use {
                metadataManager.onDeviceInitialization(token, it)
            }
        }
        // [finishBackup] will only be called when we return [TRANSPORT_OK] here
        // so we remember that we initialized successfully
        calledInitialize = true
        TRANSPORT_OK
    } catch (e: IOException) {
        Log.e(TAG, "Error initializing device", e)
        // Show error notification if we were ready for backups
        if (getBackupBackoff() == 0L) nm.onBackupError()
        TRANSPORT_ERROR
    }

    fun isAppEligibleForBackup(
        targetPackage: PackageInfo,
        @Suppress("UNUSED_PARAMETER") isFullBackup: Boolean
    ): Boolean {
        val packageName = targetPackage.packageName
        // Check that the app is not blacklisted by the user
        val enabled = settingsManager.isBackupEnabled(packageName)
        if (!enabled) Log.w(TAG, "Backup of $packageName disabled by user.")
        // We need to exclude the DocumentsProvider used to store backup data.
        // Otherwise, it gets killed when we back it up, terminating our backup.
        return enabled && targetPackage.packageName != plugin.providerPackageName
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
        if (packageName != MAGIC_PACKAGE_MANAGER) {
            // try to back up APK here as later methods are sometimes not called called
            backUpApk(context.packageManager.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES))
        }

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

    suspend fun performIncrementalBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int
    ): Int {
        cancelReason = UNKNOWN_ERROR
        val packageName = packageInfo.packageName
        if (packageName == MAGIC_PACKAGE_MANAGER) {
            // backups of package manager metadata do not respect backoff
            // we need to reject them manually when now is not a good time for a backup
            if (getBackupBackoff() != 0L) {
                return TRANSPORT_PACKAGE_REJECTED
            }
        }
        val result = kv.performBackup(packageInfo, data, flags)
        if (result == TRANSPORT_OK && packageName == MAGIC_PACKAGE_MANAGER) {
            // hook in here to back up APKs of apps that are otherwise not allowed for backup
            backUpNotAllowedPackages()
        }
        return result
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
        if (result == TRANSPORT_PACKAGE_REJECTED) cancelReason = NO_DATA
        else if (result == TRANSPORT_QUOTA_EXCEEDED) cancelReason = QUOTA_EXCEEDED
        return result
    }

    suspend fun performFullBackup(
        targetPackage: PackageInfo,
        fileDescriptor: ParcelFileDescriptor,
        flags: Int
    ): Int {
        cancelReason = UNKNOWN_ERROR
        return full.performFullBackup(targetPackage, fileDescriptor, flags)
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
        Log.i(TAG, "Cancel full backup of ${packageInfo.packageName} because of $cancelReason")
        onPackageBackupError(packageInfo)
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
    suspend fun clearBackupData(packageInfo: PackageInfo): Int {
        val packageName = packageInfo.packageName
        Log.i(TAG, "Clear Backup Data of $packageName.")
        try {
            kv.clearBackupData(packageInfo)
        } catch (e: IOException) {
            Log.w(TAG, "Error clearing K/V backup data for $packageName", e)
            return TRANSPORT_ERROR
        }
        try {
            full.clearBackupData(packageInfo)
        } catch (e: IOException) {
            Log.w(TAG, "Error clearing full backup data for $packageName", e)
            return TRANSPORT_ERROR
        }
        calledClearBackupData = true
        return TRANSPORT_OK
    }

    /**
     * Finish sending application data to the backup destination.
     * This must be called after [performIncrementalBackup], [performFullBackup], or [clearBackupData]
     * to ensure that all data is sent and the operation properly finalized.
     * Only when this method returns true can a backup be assumed to have succeeded.
     *
     * @return the same error codes as [performIncrementalBackup] or [performFullBackup].
     */
    suspend fun finishBackup(): Int = when {
        kv.hasState() -> {
            check(!full.hasState()) { "K/V backup has state, but full backup has dangling state as well" }
            onPackageBackedUp(kv.getCurrentPackage()!!) // not-null because we have state
            kv.finishBackup()
        }
        full.hasState() -> {
            check(!kv.hasState()) { "Full backup has state, but K/V backup has dangling state as well" }
            onPackageBackedUp(full.getCurrentPackage()!!) // not-null because we have state
            full.finishBackup()
        }
        calledInitialize || calledClearBackupData -> {
            calledInitialize = false
            calledClearBackupData = false
            TRANSPORT_OK
        }
        else -> throw IllegalStateException("Unexpected state in finishBackup()")
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal suspend fun backUpNotAllowedPackages() {
        Log.d(TAG, "Checking if APKs of opt-out apps need backup...")
        val notAllowedPackages = packageService.notAllowedPackages
        notAllowedPackages.forEachIndexed { i, packageInfo ->
            val packageName = packageInfo.packageName
            try {
                nm.onOptOutAppBackup(packageName, i + 1, notAllowedPackages.size)
                val packageState = if (packageInfo.isStopped()) WAS_STOPPED else NOT_ALLOWED
                val wasBackedUp = backUpApk(packageInfo, packageState)
                if (!wasBackedUp) {
                    val packageMetadata = metadataManager.getPackageMetadata(packageName)
                    val oldPackageState = packageMetadata?.state
                    if (oldPackageState != null && oldPackageState != packageState) {
                        Log.e(TAG, "Package $packageName was in $oldPackageState, update to $packageState")
                        plugin.getMetadataOutputStream().use {
                            metadataManager.onPackageBackupError(packageInfo, packageState, it)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error backing up opt-out APK of $packageName", e)
            }
        }
    }

    /**
     * Backs up an APK for the given [PackageInfo].
     *
     * @return true if a backup was performed and false if no backup was needed or it failed.
     */
    private suspend fun backUpApk(
        packageInfo: PackageInfo,
        packageState: PackageState = UNKNOWN_ERROR
    ): Boolean {
        val packageName = packageInfo.packageName
        return try {
            apkBackup.backupApkIfNecessary(packageInfo, packageState) {
                plugin.getApkOutputStream(packageInfo)
            }?.let { packageMetadata ->
                plugin.getMetadataOutputStream().use {
                    metadataManager.onApkBackedUp(packageInfo, packageMetadata, it)
                }
                true
            } ?: false
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing APK or metadata for $packageName", e)
            false
        }
    }

    private suspend fun onPackageBackedUp(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        try {
            plugin.getMetadataOutputStream().use {
                metadataManager.onPackageBackedUp(packageInfo, it)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing metadata for $packageName", e)
        }
    }

    private suspend fun onPackageBackupError(packageInfo: PackageInfo) {
        // don't bother with system apps that have no data
        if (cancelReason == NO_DATA && packageInfo.isSystemApp()) return
        val packageName = packageInfo.packageName
        try {
            plugin.getMetadataOutputStream().use {
                metadataManager.onPackageBackupError(packageInfo, cancelReason, it)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error while writing metadata for $packageName", e)
        }
    }

    private fun getBackupBackoff(): Long {
        val noBackoff = 0L
        val defaultBackoff = DAYS.toMillis(30)

        // back off if there's no storage set
        val storage = settingsManager.getStorage() ?: return defaultBackoff
        // don't back off if storage is not ejectable or available right now
        return if (!storage.isUsb || storage.getDocumentFile(context).isDirectory) noBackoff
        // otherwise back off
        else defaultBackoff
    }

}
