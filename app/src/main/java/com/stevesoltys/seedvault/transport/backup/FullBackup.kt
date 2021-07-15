package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.settings.SettingsManager
import libcore.io.IoUtils.closeQuietly
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private class FullBackupState(
    val packageInfo: PackageInfo,
    val inputFileDescriptor: ParcelFileDescriptor,
    val inputStream: InputStream,
    var outputStreamInit: (suspend () -> OutputStream)?
) {
    var outputStream: OutputStream? = null
    val packageName: String = packageInfo.packageName
    var size: Long = 0
}

const val DEFAULT_QUOTA_FULL_BACKUP = (2 * (25 * 1024 * 1024)).toLong()

private val TAG = FullBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class FullBackup(
    private val plugin: FullBackupPlugin,
    private val settingsManager: SettingsManager,
    private val inputFactory: InputFactory,
    private val headerWriter: HeaderWriter,
    private val crypto: Crypto
) {

    private var state: FullBackupState? = null

    fun hasState() = state != null

    fun getCurrentPackage() = state?.packageInfo

    fun getQuota(): Long {
        return if (settingsManager.isQuotaUnlimited()) Long.MAX_VALUE else plugin.getQuota()
    }

    fun checkFullBackupSize(size: Long): Int {
        Log.i(TAG, "Check full backup size of $size bytes.")
        return when {
            size <= 0 -> TRANSPORT_PACKAGE_REJECTED
            size > getQuota() -> TRANSPORT_QUOTA_EXCEEDED
            else -> TRANSPORT_OK
        }
    }

    /**
     * Begin the process of sending a packages' full-data archive to the backend.
     * The description of the package whose data will be delivered is provided,
     * as well as the socket file descriptor on which the transport will receive the data itself.
     *
     * If the package is not eligible for backup,
     * the transport should return [TRANSPORT_PACKAGE_REJECTED].
     * In this case the system will simply proceed with the next candidate if any,
     * or finish the full backup operation if all apps have been processed.
     *
     * After the transport returns [TRANSPORT_OK] from this method,
     * the OS will proceed to call [sendBackupData] one or more times
     * to deliver the packages' data as a streamed tarball.
     * The transport should not read() from the socket except as instructed to
     * via the [sendBackupData] method.
     *
     * After all data has been delivered to the transport, the system will call [finishBackup].
     * At this point the transport should commit the data to its datastore, if appropriate,
     * and close the socket that had been provided in [performFullBackup].
     *
     * If the transport returns [TRANSPORT_OK] from this method,
     * then the OS will always provide a matching call to [finishBackup]
     * even if sending data via [sendBackupData] failed at some point.
     *
     * @param targetPackage The package whose data is to follow.
     * @param socket The socket file descriptor through which the data will be provided.
     * If the transport returns [TRANSPORT_PACKAGE_REJECTED] here,
     * it must still close this file descriptor now;
     * otherwise it should be cached for use during succeeding calls to [sendBackupData],
     * and closed in response to [finishBackup].
     * @param flags [FLAG_USER_INITIATED] or 0.
     * @return [TRANSPORT_PACKAGE_REJECTED] to indicate that the package is not to be backed up;
     * [TRANSPORT_OK] to indicate that the OS may proceed with delivering backup data;
     * [TRANSPORT_ERROR] to indicate an error that precludes performing a backup at this time.
     */
    suspend fun performFullBackup(
        targetPackage: PackageInfo,
        socket: ParcelFileDescriptor,
        @Suppress("UNUSED_PARAMETER") flags: Int = 0
    ): Int {
        if (state != null) throw AssertionError()
        Log.i(TAG, "Perform full backup for ${targetPackage.packageName}.")

        // create new state
        val inputStream = inputFactory.getInputStream(socket)
        state = FullBackupState(targetPackage, socket, inputStream) {
            Log.d(TAG, "Initializing OutputStream for ${targetPackage.packageName}.")
            // get OutputStream to write backup data into
            val outputStream = try {
                plugin.getOutputStream(targetPackage)
            } catch (e: IOException) {
                "Error getting OutputStream for full backup of ${targetPackage.packageName}".let {
                    Log.e(TAG, it, e)
                }
                throw(e)
            }
            // store version header
            val state = this.state ?: throw AssertionError()
            val header = VersionHeader(packageName = state.packageName)
            try {
                headerWriter.writeVersion(outputStream, header)
                crypto.encryptHeader(outputStream, header)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing backup header", e)
                throw(e)
            }
            outputStream
        } // this lambda is only called before we actually write backup data the first time
        return TRANSPORT_OK
    }

    suspend fun sendBackupData(numBytes: Int): Int {
        val state = this.state
            ?: throw AssertionError("Attempted sendBackupData before performFullBackup")

        // check if size fits quota
        state.size += numBytes
        val quota = getQuota()
        if (state.size > quota) {
            Log.w(
                TAG,
                "Full backup of additional $numBytes exceeds quota of $quota with ${state.size}."
            )
            return TRANSPORT_QUOTA_EXCEEDED
        }

        return try {
            // get output stream or initialize it, if it does not yet exist
            check((state.outputStream != null) xor (state.outputStreamInit != null)) {
                "No OutputStream xor no StreamGetter"
            }
            val outputStream = state.outputStream ?: suspend {
                val stream = state.outputStreamInit!!() // not-null due to check above
                state.outputStream = stream
                stream
            }()
            state.outputStreamInit = null // the stream init lambda is not needed beyond that point

            // read backup data, encrypt it and write it to output stream
            val payload = ByteArray(numBytes)
            val read = state.inputStream.read(payload, 0, numBytes)
            if (read != numBytes) throw EOFException("Read $read bytes instead of $numBytes.")
            crypto.encryptSegment(outputStream, payload)
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Error handling backup data for ${state.packageName}: ", e)
            TRANSPORT_ERROR
        }
    }

    @Throws(IOException::class)
    suspend fun clearBackupData(packageInfo: PackageInfo) {
        plugin.removeDataOfPackage(packageInfo)
    }

    suspend fun cancelFullBackup() {
        Log.i(TAG, "Cancel full backup")
        val state = this.state ?: throw AssertionError("No state when canceling")
        try {
            plugin.removeDataOfPackage(state.packageInfo)
        } catch (e: IOException) {
            Log.w(TAG, "Error cancelling full backup for ${state.packageName}", e)
        }
        clearState()
        // TODO roll back to the previous known-good archive
    }

    fun finishBackup(): Int {
        Log.i(TAG, "Finish full backup of ${state!!.packageName}.")
        return clearState()
    }

    private fun clearState(): Int {
        val state = this.state ?: throw AssertionError("Trying to clear empty state.")
        return try {
            state.outputStream?.flush()
            closeQuietly(state.outputStream)
            closeQuietly(state.inputStream)
            closeQuietly(state.inputFileDescriptor)
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.w(TAG, "Error when clearing state", e)
            TRANSPORT_ERROR
        } finally {
            this.state = null
        }
    }

}
