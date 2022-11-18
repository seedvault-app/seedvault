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
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import java.io.IOException
import java.util.zip.GZIPOutputStream

class KVBackupState(
    internal val packageInfo: PackageInfo,
    val token: Long,
    val name: String,
    val db: KVDb,
) {
    var needsUpload: Boolean = false
}

const val DEFAULT_QUOTA_KEY_VALUE_BACKUP = (2 * (5 * 1024 * 1024)).toLong()

private val TAG = KVBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVBackup(
    private val plugin: StoragePlugin,
    private val settingsManager: SettingsManager,
    private val inputFactory: InputFactory,
    private val crypto: Crypto,
    private val dbManager: KvDbManager,
) {

    private var state: KVBackupState? = null

    fun hasState() = state != null

    fun getCurrentPackage() = state?.packageInfo

    fun getQuota(): Long = if (settingsManager.isQuotaUnlimited()) {
        Long.MAX_VALUE
    } else {
        DEFAULT_QUOTA_KEY_VALUE_BACKUP
    }

    suspend fun performBackup(
        packageInfo: PackageInfo,
        data: ParcelFileDescriptor,
        flags: Int,
        token: Long,
        salt: String,
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
        val name = crypto.getNameForPackage(salt, packageName)
        val db = dbManager.getDb(packageName)
        this.state = KVBackupState(packageInfo, token, name, db)

        // no need for backup when no data has changed
        if (dataNotChanged) {
            data.close()
            return TRANSPORT_OK
        }

        // check if we have existing data for the given package
        val hasDataForPackage = dbManager.existsDb(packageName)
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
                clearBackupData(packageInfo, token, salt)
            } catch (e: IOException) {
                Log.w(TAG, "Error clearing backup data for ${packageInfo.packageName}.", e)
            }
        }

        // parse and store the K/V updates
        return storeRecords(data)
    }

    private fun storeRecords(data: ParcelFileDescriptor): Int {
        val state = this.state ?: error("No state in storeRecords")
        // apply the delta operations
        for (result in parseBackupStream(data)) {
            if (result is Result.Error) {
                Log.e(TAG, "Exception reading backup input", result.exception)
                return backupError(TRANSPORT_ERROR)
            }
            state.needsUpload = if (state.packageInfo.packageName == MAGIC_PACKAGE_MANAGER) {
                // Don't upload, if we currently can't do backups.
                // If we tried, we would fail @pm@ backup which causes the system to do a re-init.
                // See: https://github.com/seedvault-app/seedvault/issues/102
                // K/V backups (typically starting with package manager metadata - @pm@)
                // are scheduled with JobInfo.Builder#setOverrideDeadline()
                // and thus do not respect backoff.
                settingsManager.canDoBackupNow()
            } else {
                // all other packages always need upload
                true
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
    suspend fun clearBackupData(packageInfo: PackageInfo, token: Long, salt: String) {
        Log.i(TAG, "Clearing K/V data of ${packageInfo.packageName}")
        val name = state?.name ?: crypto.getNameForPackage(salt, packageInfo.packageName)
        plugin.removeData(token, name)
        if (!dbManager.deleteDb(packageInfo.packageName)) throw IOException()
    }

    suspend fun finishBackup(): Int {
        val state = this.state ?: error("No state in finishBackup")
        val packageName = state.packageInfo.packageName
        Log.i(TAG, "Finish K/V Backup of $packageName - needs upload: ${state.needsUpload}")

        return try {
            if (state.needsUpload) uploadDb(state.token, state.name, packageName, state.db)
            else state.db.close()
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Error uploading DB", e)
            TRANSPORT_ERROR
        } finally {
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

    @Throws(IOException::class)
    private suspend fun uploadDb(
        token: Long,
        name: String,
        packageName: String,
        db: KVDb,
    ) {
        db.vacuum()
        db.close()

        plugin.getOutputStream(token, name).use { outputStream ->
            outputStream.write(ByteArray(1) { VERSION })
            val ad = getADForKV(VERSION, packageName)
            crypto.newEncryptingStream(outputStream, ad).use { encryptedStream ->
                GZIPOutputStream(encryptedStream).use { gZipStream ->
                    dbManager.getDbInputStream(packageName).use { inputStream ->
                        inputStream.copyTo(gZipStream)
                    }
                }
            }
        }
        Log.d(TAG, "Uploaded db file for $packageName")
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
