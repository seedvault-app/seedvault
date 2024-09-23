/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.FLAG_USER_INITIATED
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.repo.BackupData
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import org.calyxos.seedvault.core.backends.isOutOfSpace
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

private class FullBackupState(
    val packageInfo: PackageInfo,
    val inputFileDescriptor: ParcelFileDescriptor,
    val inputStream: InputStream,
) {
    val packageName: String = packageInfo.packageName
    var size: Long = 0
}

private val TAG = FullBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class FullBackup(
    private val settingsManager: SettingsManager,
    private val nm: BackupNotificationManager,
    private val backupReceiver: BackupReceiver,
    private val inputFactory: InputFactory,
) {

    private var state: FullBackupState? = null

    val hasState: Boolean get() = state != null
    val currentPackageInfo get() = state?.packageInfo
    val quota get() = settingsManager.quota

    fun checkFullBackupSize(size: Long): Int {
        Log.i(TAG, "Check full backup size of $size bytes.")
        return when {
            size <= 0 -> TRANSPORT_PACKAGE_REJECTED
            size > quota -> TRANSPORT_QUOTA_EXCEEDED
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
    fun performFullBackup(
        targetPackage: PackageInfo,
        socket: ParcelFileDescriptor,
        @Suppress("UNUSED_PARAMETER") flags: Int = 0,
    ): Int {
        if (state != null) error("state wasn't initialized for $targetPackage")
        val packageName = targetPackage.packageName
        Log.i(TAG, "Perform full backup for $packageName.")

        // create new state
        val inputStream = inputFactory.getInputStream(socket)
        state = FullBackupState(targetPackage, socket, inputStream)
        return TRANSPORT_OK
    }

    suspend fun sendBackupData(numBytes: Int): Int {
        val state = this.state ?: error("Attempted sendBackupData before performFullBackup")

        // check if size fits quota
        val newSize = state.size + numBytes
        if (newSize > quota) {
            Log.w(
                TAG,
                "Full backup of additional $numBytes exceeds quota of $quota with $newSize."
            )
            return TRANSPORT_QUOTA_EXCEEDED
        }

        return try {
            // read backup data and write it to encrypted output stream
            val payload = ByteArray(numBytes)
            val read = state.inputStream.read(payload, 0, numBytes)
            if (read != numBytes) throw EOFException("Read $read bytes instead of $numBytes.")
            backupReceiver.addBytes(getOwner(state.packageName), payload)
            state.size += numBytes
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Error handling backup data for ${state.packageName}: ", e)
            if (e.isOutOfSpace()) nm.onInsufficientSpaceError()
            TRANSPORT_ERROR
        }
    }

    suspend fun cancelFullBackup() {
        val state = this.state ?: error("No state when canceling")
        Log.i(TAG, "Cancel full backup for ${state.packageName}")
        // finalize the receiver
        try {
            backupReceiver.finalize(getOwner(state.packageName))
        } catch (e: Exception) {
            // as the backup was cancelled anyway, we don't care if finalizing had an error
            Log.e(TAG, "Error finalizing backup in cancelFullBackup().", e)
        }
        // If the transport receives this callback, it will *not* receive a call to [finishBackup].
        // It needs to tear down any ongoing backup state here.
        clearState()
    }

    /**
     * Returns a pair of the [BackupData] after finalizing last chunks and the total backup size.
     */
    @Throws(IOException::class)
    suspend fun finishBackup(): BackupData {
        val state = this.state ?: error("No state when finishing")
        Log.i(TAG, "Finish full backup of ${state.packageName}. Wrote ${state.size} bytes")
        val result = try {
            backupReceiver.finalize(getOwner(state.packageName))
        } finally {
            clearState()
        }
        return result
    }

    private fun clearState() {
        val state = this.state ?: error("Trying to clear empty state.")
        closeLogging(state.inputStream)
        closeLogging(state.inputFileDescriptor)
        this.state = null
    }

    private fun getOwner(packageName: String) = "FullBackup $packageName"

    private fun closeLogging(closable: Closeable?) = try {
        closable?.close()
    } catch (e: Exception) {
        Log.w(TAG, "Error closing: ", e)
    }

}
