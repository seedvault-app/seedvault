package com.stevesoltys.backup.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.backup.BackupNotificationManager
import com.stevesoltys.backup.metadata.MetadataWriter
import java.io.IOException

private val TAG = BackupCoordinator::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class BackupCoordinator(
        private val plugin: BackupPlugin,
        private val kv: KVBackup,
        private val full: FullBackup,
        private val metadataWriter: MetadataWriter,
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
            writeBackupMetadata()
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

    fun requestBackupTime() = kv.requestBackupTime()

    fun performIncrementalBackup(packageInfo: PackageInfo, data: ParcelFileDescriptor, flags: Int) =
            kv.performBackup(packageInfo, data, flags)

    // ------------------------------------------------------------------------------------
    // Full backup
    //

    fun requestFullBackupTime() = full.requestFullBackupTime()

    fun checkFullBackupSize(size: Long) = full.checkFullBackupSize(size)

    fun performFullBackup(targetPackage: PackageInfo, fileDescriptor: ParcelFileDescriptor, flags: Int) =
            full.performFullBackup(targetPackage, fileDescriptor, flags)

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
    private fun writeBackupMetadata() {
        val outputStream = plugin.getMetadataOutputStream()
        metadataWriter.write(outputStream)
    }

}
