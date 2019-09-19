package com.stevesoltys.backup.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.backup.BackupNotificationManager
import com.stevesoltys.backup.metadata.MetadataWriter
import com.stevesoltys.backup.settings.SettingsManager
import java.io.IOException
import java.util.concurrent.TimeUnit.MINUTES

private val TAG = BackupCoordinator::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class BackupCoordinator(
        private val context: Context,
        private val plugin: BackupPlugin,
        private val kv: KVBackup,
        private val full: FullBackup,
        private val metadataWriter: MetadataWriter,
        private val settingsManager: SettingsManager,
        private val nm: BackupNotificationManager) {

    private var calledInitialize = false
    private var calledClearBackupData = false

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
     * Even if the transport is in a state
     * such that attempting to "initialize" the backend storage is meaningless -
     * for example, if there is no current live data-set at all,
     * or there is no authenticated account under which to store the data remotely -
     * the transport should return [TRANSPORT_OK] here
     * and treat the initializeDevice() / finishBackup() pair as a graceful no-op.
     *
     * @return One of [TRANSPORT_OK] (OK so far) or
     * [TRANSPORT_ERROR] (to retry following network error or other failure).
     */
    fun initializeDevice(): Int {
        Log.i(TAG, "Initialize Device!")
        return try {
            plugin.initializeDevice()
            writeBackupMetadata(settingsManager.getBackupToken())
            // [finishBackup] will only be called when we return [TRANSPORT_OK] here
            // so we remember that we initialized successfully
            calledInitialize = true
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing device", e)
            nm.onBackupError()
            TRANSPORT_ERROR
        }
    }

    fun isAppEligibleForBackup(targetPackage: PackageInfo, @Suppress("UNUSED_PARAMETER") isFullBackup: Boolean): Boolean {
        // We need to exclude the DocumentsProvider used to store backup data.
        // Otherwise, it gets killed when we back it up, terminating our backup.
        return targetPackage.packageName != plugin.providerPackageName
    }

    fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long {
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

    fun performIncrementalBackup(packageInfo: PackageInfo, data: ParcelFileDescriptor, flags: Int): Int {
        val result = kv.performBackup(packageInfo, data, flags)
        if (result == TRANSPORT_OK) settingsManager.saveNewBackupTime()
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

    fun checkFullBackupSize(size: Long) = full.checkFullBackupSize(size)

    fun performFullBackup(targetPackage: PackageInfo, fileDescriptor: ParcelFileDescriptor, flags: Int): Int {
        val result = full.performFullBackup(targetPackage, fileDescriptor, flags)
        if (result == TRANSPORT_OK) settingsManager.saveNewBackupTime()
        return result
    }

    fun sendBackupData(numBytes: Int) = full.sendBackupData(numBytes)

    fun cancelFullBackup() = full.cancelFullBackup()

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

    fun finishBackup(): Int = when {
        kv.hasState() -> {
            check(!full.hasState())
            kv.finishBackup()
        }
        full.hasState() -> {
            check(!kv.hasState())
            full.finishBackup()
        }
        calledInitialize || calledClearBackupData -> {
            calledInitialize = false
            calledClearBackupData = false
            TRANSPORT_OK
        }
        else -> throw IllegalStateException()
    }

    @Throws(IOException::class)
    private fun writeBackupMetadata(token: Long) {
        val outputStream = plugin.getMetadataOutputStream()
        metadataWriter.write(outputStream, token)
    }

    private fun getBackupBackoff(): Long {
        val noBackoff = 0L
        val defaultBackoff = MINUTES.toMillis(10)

        // back off if there's no storage set
        val storage = settingsManager.getStorage() ?: return defaultBackoff
        // don't back off if storage is not ejectable or available right now
        return if (!storage.isUsb || storage.getDocumentFile(context).isDirectory) noBackoff
        // otherwise back off
        else defaultBackoff
    }

}
