/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

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
import com.stevesoltys.seedvault.NO_DATA_END_SENTINEL
import com.stevesoltys.seedvault.repo.BackupData
import com.stevesoltys.seedvault.repo.BackupReceiver
import java.io.IOException

class KVBackupState(
    internal val packageInfo: PackageInfo,
    val db: KVDb,
)

private val TAG = KVBackup::class.java.simpleName

internal class KVBackup(
    private val backupReceiver: BackupReceiver,
    private val inputFactory: InputFactory,
    private val dbManager: KvDbManager,
) {

    private var state: KVBackupState? = null

    val hasState get() = state != null
    val currentPackageInfo get() = state?.packageInfo

    fun performBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int,
    ): Int {
        val dataNotChanged = flags and FLAG_DATA_NOT_CHANGED != 0
        val isIncremental = flags and FLAG_INCREMENTAL != 0
        val isNonIncremental = flags and FLAG_NON_INCREMENTAL != 0
        val packageName = packageInfo.packageName
        when {
            dataNotChanged -> Log.i(TAG, "No K/V backup data has changed for $packageName")
            isIncremental -> Log.i(TAG, "Performing incremental K/V backup for $packageName")
            isNonIncremental -> Log.i(TAG, "Performing non-incremental K/V backup for $packageName")
            else -> Log.i(TAG, "Performing K/V backup for $packageName")
        }
        check(state == null) { "Have unexpected state for ${state?.packageInfo?.packageName}" }
        // This fake package name just signals that we've seen all packages without new data
        if (packageName == NO_DATA_END_SENTINEL) return TRANSPORT_OK

        // initialize state
        state = KVBackupState(packageInfo = packageInfo, db = dbManager.getDb(packageName))

        // handle case where data hasn't changed since last backup
        val hasDataForPackage = dbManager.existsDb(packageName)
        if (dataNotChanged) {
            data.close()
            return if (hasDataForPackage) {
                TRANSPORT_OK
            } else {
                Log.w(TAG, "No previous data for $packageName, requesting non-incremental backup!")
                backupError(TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED)
            }
        }
        // check if we have existing data for the given package
        if (isIncremental && !hasDataForPackage) {
            Log.w(
                TAG, "Requested incremental, but transport currently stores no data" +
                    " for $packageName, requesting non-incremental retry."
            )
            data.close()
            return backupError(TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED)
        }
        // check if we have existing data, but the system wants clean slate
        if (isNonIncremental && hasDataForPackage) {
            Log.w(TAG, "Requested non-incremental, deleting existing data...")
            dbManager.deleteDb(packageInfo.packageName)
            // KvBackupInstrumentationTest tells us that the DB gets re-created automatically
        }
        // parse and store the K/V updates
        return data.use {
            storeRecords(it)
        }
    }

    private fun storeRecords(data: ParcelFileDescriptor): Int {
        val state = this.state ?: error("No state in storeRecords")
        // apply the delta operations
        for (result in parseBackupStream(data)) {
            if (result is Result.Error) {
                Log.e(TAG, "Exception reading backup input", result.exception)
                return backupError(TRANSPORT_ERROR)
            }
            val op = (result as Result.Ok).result
            if (op.value == null) {
                Log.e(TAG, "Deleting record with key ${op.key}")
                state.db.delete(op.key)
            } else {
                state.db.put(op.key, op.value)
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
                if (!changeSet.readNextHeader()) return@generateSequence null // end the sequence
            } catch (e: IOException) {
                Log.e(TAG, "Error reading next header", e)
                return@generateSequence Result.Error(e)
            }
            // encode key
            val key = changeSet.key
            val dataSize = changeSet.dataSize

            // read value
            val value = if (dataSize >= 0) {
                Log.v(TAG, "  Delta operation key $key   size $dataSize")
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
            Result.Ok(KVOperation(key, value))
        }
    }

    @Throws(IOException::class)
    suspend fun finishBackup(): BackupData {
        val state = this.state ?: error("No state in finishBackup")
        val packageName = state.packageInfo.packageName
        val owner = "KV $packageName"
        Log.i(TAG, "Finish K/V Backup of $packageName")

        try {
            state.db.vacuum()
            state.db.close()
            val backupData = dbManager.getDbInputStream(packageName).use { inputStream ->
                backupReceiver.readFromStream(owner, inputStream)
            }
            Log.d(TAG, "Uploaded db file for $packageName.")
            return backupData
        } finally { // exceptions bubble up
            this.state = null
        }
    }

    /**
     * Method to reset state,
     * because [finishBackup] is not called when we don't return [TRANSPORT_OK].
     */
    private fun backupError(result: Int): Int {
        val state = this.state ?: error("No state in backupError")
        val packageName = state.packageInfo.packageName
        Log.i(TAG, "Resetting state because of K/V Backup error of $packageName")

        state.db.close()
        this.state = null
        return result
    }

    private class KVOperation(
        val key: String,
        /**
         * value is null when this is a deletion operation
         */
        val value: ByteArray?,
    )

    private sealed class Result<out T> {
        class Ok<out T>(val result: T) : Result<T>()
        class Error(val exception: Exception) : Result<Nothing>()
    }

}
