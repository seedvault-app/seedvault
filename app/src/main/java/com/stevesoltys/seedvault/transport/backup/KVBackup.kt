package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.*
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.VersionHeader
import libcore.io.IoUtils.closeQuietly
import java.io.IOException

class KVBackupState(internal val packageName: String)

const val DEFAULT_QUOTA_KEY_VALUE_BACKUP = (2 * (5 * 1024 * 1024)).toLong()

private val TAG = KVBackup::class.java.simpleName

internal class KVBackup(
        private val plugin: KVBackupPlugin,
        private val inputFactory: InputFactory,
        private val headerWriter: HeaderWriter,
        private val crypto: Crypto) {

    private var state: KVBackupState? = null

    fun hasState() = state != null

    fun getQuota(): Long = plugin.getQuota()

    fun performBackup(packageInfo: PackageInfo, data: ParcelFileDescriptor, flags: Int): Int {
        val isIncremental = flags and FLAG_INCREMENTAL != 0
        val isNonIncremental = flags and FLAG_NON_INCREMENTAL != 0
        val packageName = packageInfo.packageName

        when {
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
        if (this.state != null) throw AssertionError()
        this.state = KVBackupState(packageInfo.packageName)

        // check if we have existing data for the given package
        val hasDataForPackage = try {
            plugin.hasDataForPackage(packageInfo)
        } catch (e: IOException) {
            Log.e(TAG, "Error checking for existing data for ${packageInfo.packageName}.", e)
            return backupError(TRANSPORT_ERROR)
        }
        if (isIncremental && !hasDataForPackage) {
            Log.w(TAG, "Requested incremental, but transport currently stores no data $packageName, requesting non-incremental retry.")
            return backupError(TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED)
        }

        // TODO check if package is over-quota

        if (isNonIncremental && hasDataForPackage) {
            Log.w(TAG, "Requested non-incremental, deleting existing data.")
            try {
                clearBackupData(packageInfo)
            } catch (e: IOException) {
                Log.w(TAG, "Error clearing backup data for ${packageInfo.packageName}.", e)
            }
        }

        // ensure there's a place to store K/V for the given package
        try {
            plugin.ensureRecordStorageForPackage(packageInfo)
        } catch (e: IOException) {
            Log.e(TAG, "Error ensuring storage for ${packageInfo.packageName}.", e)
            return backupError(TRANSPORT_ERROR)
        }

        // parse and store the K/V updates
        return storeRecords(packageInfo, data)
    }

    private fun storeRecords(packageInfo: PackageInfo, data: ParcelFileDescriptor): Int {
        // apply the delta operations
        for (result in parseBackupStream(data)) {
            if (result is Result.Error) {
                Log.e(TAG, "Exception reading backup input", result.exception)
                return backupError(TRANSPORT_ERROR)
            }
            val op = (result as Result.Ok).result
            try {
                if (op.value == null) {
                    Log.e(TAG, "Deleting record with base64Key ${op.base64Key}")
                    plugin.deleteRecord(packageInfo, op.base64Key)
                } else {
                    val outputStream = plugin.getOutputStreamForRecord(packageInfo, op.base64Key)
                    val header = VersionHeader(packageName = packageInfo.packageName, key = op.key)
                    headerWriter.writeVersion(outputStream, header)
                    crypto.encryptHeader(outputStream, header)
                    crypto.encryptMultipleSegments(outputStream, op.value)
                    outputStream.flush()
                    closeQuietly(outputStream)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Unable to update base64Key file for base64Key ${op.base64Key}", e)
                return backupError(TRANSPORT_ERROR)
            }
        }
        return TRANSPORT_OK
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
                if (!changeSet.readNextHeader()) return@generateSequence null  // end the sequence
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
    fun clearBackupData(packageInfo: PackageInfo) {
        plugin.removeDataOfPackage(packageInfo)
    }

    fun finishBackup(): Int {
        Log.i(TAG, "Finish K/V Backup of ${state!!.packageName}")
        state = null
        return TRANSPORT_OK
    }

    /**
     * Method to reset state,
     * because [finishBackup] is not called when we don't return [TRANSPORT_OK].
     */
    private fun backupError(result: Int): Int {
        Log.i(TAG, "Resetting state because of K/V Backup error of ${state!!.packageName}")
        state = null
        return result
    }

    private class KVOperation(
            internal val key: String,
            internal val base64Key: String,
            /**
             * value is null when this is a deletion operation
             */
            internal val value: ByteArray?
    )

    private sealed class Result<out T> {
        class Ok<out T>(val result: T) : Result<T>()
        class Error(val exception: Exception) : Result<Nothing>()
    }

}
