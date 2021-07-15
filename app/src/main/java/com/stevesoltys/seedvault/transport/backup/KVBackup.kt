package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.FLAG_DATA_NOT_CHANGED
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import libcore.io.IoUtils.closeQuietly
import java.io.IOException

class KVBackupState(internal val packageInfo: PackageInfo)

const val DEFAULT_QUOTA_KEY_VALUE_BACKUP = (2 * (5 * 1024 * 1024)).toLong()

private val TAG = KVBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVBackup(
    private val plugin: KVBackupPlugin,
    private val settingsManager: SettingsManager,
    private val inputFactory: InputFactory,
    private val headerWriter: HeaderWriter,
    private val crypto: Crypto,
    private val nm: BackupNotificationManager
) {

    private var state: KVBackupState? = null

    fun hasState() = state != null

    fun getCurrentPackage() = state?.packageInfo

    fun getQuota(): Long {
        return if (settingsManager.isQuotaUnlimited()) Long.MAX_VALUE else plugin.getQuota()
    }

    suspend fun performBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int
    ): Int {
        val dataNotChanged = flags and FLAG_DATA_NOT_CHANGED != 0
        val isIncremental = flags and FLAG_INCREMENTAL != 0
        val isNonIncremental = flags and FLAG_NON_INCREMENTAL != 0
        val packageName = packageInfo.packageName

        when {
            dataNotChanged -> {
                Log.i(TAG, "No K/V backup data has changed for $packageName")
            }
            isIncremental -> {
                Log.i(TAG, "Performing incremental K/V backup for $packageName")
            }
            isNonIncremental -> {
                Log.i(TAG, "Performing non-incremental K/V backup for $packageName")
            }
            else -> {
                Log.i(TAG, "Performing K/V backup for $packageName")
            }
        }

        // initialize state
        val state = this.state
        if (state != null) {
            throw AssertionError("Have state for ${state.packageInfo.packageName}")
        }
        this.state = KVBackupState(packageInfo)

        // no need for backup when no data has changed
        if (dataNotChanged) {
            data.close()
            return TRANSPORT_OK
        }

        // check if we have existing data for the given package
        val hasDataForPackage = try {
            plugin.hasDataForPackage(packageInfo)
        } catch (e: IOException) {
            Log.e(TAG, "Error checking for existing data for ${packageInfo.packageName}.", e)
            return backupError(TRANSPORT_ERROR)
        }
        if (isIncremental && !hasDataForPackage) {
            Log.w(
                TAG, "Requested incremental, but transport currently stores no data" +
                    " for $packageName, requesting non-incremental retry."
            )
            return backupError(TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED)
        }

        // TODO check if package is over-quota and respect unlimited setting

        if (isNonIncremental && hasDataForPackage) {
            Log.w(TAG, "Requested non-incremental, deleting existing data.")
            try {
                clearBackupData(packageInfo)
            } catch (e: IOException) {
                Log.w(TAG, "Error clearing backup data for ${packageInfo.packageName}.", e)
            }
        }

        // parse and store the K/V updates
        return storeRecords(packageInfo, data)
    }

    private suspend fun storeRecords(packageInfo: PackageInfo, data: ParcelFileDescriptor): Int {
        val backupSequence: Iterable<Result<KVOperation>>
        val pmRecordNumber: Int?
        if (packageInfo.packageName == MAGIC_PACKAGE_MANAGER) {
            // Since the package manager has many small keys to store,
            // and this can be slow, especially on cloud-based storage,
            // we get the entire data set first, so we can show progress notifications.
            val list = parseBackupStream(data).toList()
            backupSequence = list
            pmRecordNumber = list.size
        } else {
            backupSequence = parseBackupStream(data).asIterable()
            pmRecordNumber = null
        }
        // apply the delta operations
        var i = 1
        for (result in backupSequence) {
            if (result is Result.Error) {
                Log.e(TAG, "Exception reading backup input", result.exception)
                return backupError(TRANSPORT_ERROR)
            }
            val op = (result as Result.Ok).result
            try {
                storeRecord(packageInfo, op, i++, pmRecordNumber)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to update base64Key file for base64Key ${op.base64Key}", e)
                // Returning something more forgiving such as TRANSPORT_PACKAGE_REJECTED
                // will still make the entire backup fail.
                // TODO However, TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED might buy us a retry,
                //  we would just need to be careful not to create an infinite loop
                //  for permanent errors.
                return backupError(TRANSPORT_ERROR)
            }
        }
        return TRANSPORT_OK
    }

    @Throws(IOException::class)
    private suspend fun storeRecord(
        packageInfo: PackageInfo,
        op: KVOperation,
        currentNum: Int,
        pmRecordNumber: Int?
    ) {
        // update notification for package manager backup
        if (pmRecordNumber != null) {
            nm.onPmKvBackup(op.key, currentNum, pmRecordNumber)
        }
        // check if record should get deleted
        if (op.value == null) {
            Log.e(TAG, "Deleting record with base64Key ${op.base64Key}")
            plugin.deleteRecord(packageInfo, op.base64Key)
        } else {
            val outputStream = plugin.getOutputStreamForRecord(packageInfo, op.base64Key)
            try {
                val header = VersionHeader(
                    packageName = packageInfo.packageName,
                    key = op.key
                )
                headerWriter.writeVersion(outputStream, header)
                crypto.encryptHeader(outputStream, header)
                crypto.encryptMultipleSegments(outputStream, op.value)
                outputStream.flush()
            } finally {
                closeQuietly(outputStream)
            }
        }
    }

    /**
     * Parses a backup stream into individual key/value operations
     */
    private fun parseBackupStream(data: ParcelFileDescriptor): Sequence<Result<KVOperation>> {
        val changeSet = inputFactory.getBackupDataInput(data)

        // Each K/V pair in the restore set is kept in its own file, named by the record key.
        // Wind through the data file, extracting individual record operations
        // and building a sequence of all the updates to apply in this update.
        return generateSequence {
            // read the next header or end the sequence in case of error or no more headers
            try {
                if (!changeSet.readNextHeader()) return@generateSequence null // end the sequence
            } catch (e: IOException) {
                Log.e(TAG, "Error reading next header", e)
                return@generateSequence Result.Error(e)
            }
            // encode key
            val key = changeSet.key
            val base64Key = key.encodeBase64()
            val dataSize = changeSet.dataSize

            // read value
            val value = if (dataSize >= 0) {
                Log.v(TAG, "  Delta operation key $key   size $dataSize   key64 $base64Key")
                val bytes = ByteArray(dataSize)
                val bytesRead = try {
                    changeSet.readEntityData(bytes, 0, dataSize)
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading entity data for key $key", e)
                    return@generateSequence Result.Error(e)
                }
                if (bytesRead != dataSize) {
                    Log.w(TAG, "Expecting $dataSize bytes, but only read $bytesRead.")
                }
                bytes
            } else null
            // add change operation to the sequence
            Result.Ok(KVOperation(key, base64Key, value))
        }
    }

    @Throws(IOException::class)
    suspend fun clearBackupData(packageInfo: PackageInfo) {
        plugin.removeDataOfPackage(packageInfo)
    }

    fun finishBackup(): Int {
        Log.i(TAG, "Finish K/V Backup of ${state!!.packageInfo.packageName}")
        plugin.packageFinished(state!!.packageInfo)
        state = null
        return TRANSPORT_OK
    }

    /**
     * Method to reset state,
     * because [finishBackup] is not called when we don't return [TRANSPORT_OK].
     */
    private fun backupError(result: Int): Int {
        "Resetting state because of K/V Backup error of ${state!!.packageInfo.packageName}".let {
            Log.i(TAG, it)
        }
        plugin.packageFinished(state!!.packageInfo)
        state = null
        return result
    }

    private class KVOperation(
        val key: String,
        val base64Key: String,
        /**
         * value is null when this is a deletion operation
         */
        val value: ByteArray?
    )

    private sealed class Result<out T> {
        class Ok<out T>(val result: T) : Result<T>()
        class Error(val exception: Exception) : Result<Nothing>()
    }

}
