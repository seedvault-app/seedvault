/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.getADForFull
import com.stevesoltys.seedvault.repo.Loader
import libcore.io.IoUtils.closeQuietly
import org.calyxos.seedvault.core.backends.AppBackupFileType.Blob
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

private class FullRestoreState(
    val version: Byte,
    val packageInfo: PackageInfo,
    val blobHandles: List<Blob>? = null,
    val token: Long? = null,
    val name: String? = null,
) {
    var inputStream: InputStream? = null
}

private val TAG = FullRestore::class.java.simpleName

internal class FullRestore(
    private val backendManager: BackendManager,
    private val loader: Loader,
    @Suppress("Deprecation")
    private val legacyPlugin: LegacyStoragePlugin,
    private val outputFactory: OutputFactory,
    private val headerReader: HeaderReader,
    private val crypto: Crypto,
) {

    private val backend get() = backendManager.backend
    private var state: FullRestoreState? = null

    val hasState get() = state != null

    /**
     * Return true if there is data stored for the given package.
     *
     * Deprecated. Use only for v0 backups.
     */
    @Throws(IOException::class)
    @Deprecated("Use BackupPlugin#hasData() instead")
    suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return legacyPlugin.hasDataForFullPackage(token, packageInfo)
    }

    /**
     * This prepares to restore the given package from the given restore token.
     *
     * It is possible that the system decides to not restore the package.
     * Then a new state will be initialized right away without calling other methods.
     */
    fun initializeState(version: Byte, packageInfo: PackageInfo, blobHandles: List<Blob>) {
        state = FullRestoreState(version, packageInfo, blobHandles)
    }

    fun initializeStateV1(token: Long, name: String, packageInfo: PackageInfo) {
        state = FullRestoreState(1, packageInfo, null, token, name)
    }

    fun initializeStateV0(token: Long, packageInfo: PackageInfo) {
        state = FullRestoreState(0x00, packageInfo, null, token)
    }

    /**
     * Ask the transport to provide data for the "current" package being restored.
     *
     * The transport writes some data to the socket supplied to this call,
     * and returns the number of bytes written.
     * The system will then read that many bytes
     * and stream them to the application's agent for restore,
     * then will call this method again to receive the next chunk of the archive.
     * This sequence will be repeated until the transport returns zero
     * indicating that all of the package's data has been delivered
     * (or returns a negative value indicating a hard error condition at the transport level).
     *
     * The transport should always close this socket when returning from this method.
     * Do not cache this socket across multiple calls or you may leak file descriptors.
     *
     * @param socket The file descriptor for delivering the streamed archive.
     * The transport must close this socket in all cases when returning from this method.
     * @return [NO_MORE_DATA] when no more data for the current package is available.
     * A positive value indicates the presence of that many bytes to be delivered to the app.
     * A value of zero indicates that no data was deliverable at this time,
     * but the restore is still running and the caller should retry.
     * [TRANSPORT_PACKAGE_REJECTED] means that the package's restore operation should be aborted,
     * but that the transport itself is still in a good state
     * and so a multiple-package restore sequence can still be continued.
     * Any other negative value such as [TRANSPORT_ERROR] is treated as a fatal error condition
     * that aborts all further restore operations on the current dataset.
     */
    suspend fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int = socket.use { pfd ->
        val state = this.state ?: throw IllegalStateException("no state")
        val packageName = state.packageInfo.packageName

        if (state.inputStream == null) {
            Log.i(TAG, "First Chunk, initializing package input stream.")
            try {
                when (state.version) {
                    0.toByte() -> {
                        val token = state.token ?: error("no token for v0 backup")
                        val inputStream =
                            legacyPlugin.getInputStreamForPackage(token, state.packageInfo)
                        val version = headerReader.readVersion(inputStream, state.version)
                        @Suppress("deprecation")
                        crypto.decryptHeader(inputStream, version, packageName)
                        state.inputStream = inputStream
                    }
                    1.toByte() -> {
                        val token = state.token ?: error("no token for v1 backup")
                        val name = state.name ?: error("no name for v1 backup")
                        val handle = LegacyAppBackupFile.Blob(token, name)
                        val inputStream = backend.load(handle)
                        val version = headerReader.readVersion(inputStream, state.version)
                        val ad = getADForFull(version, packageName)
                        state.inputStream = crypto.newDecryptingStreamV1(inputStream, ad)
                    }
                    else -> {
                        val handles = state.blobHandles ?: error("no blob handles for v2")
                        state.inputStream = loader.loadFiles(handles)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Error getting input stream for $packageName", e)
                return TRANSPORT_PACKAGE_REJECTED
            } catch (e: SecurityException) {
                Log.e(TAG, "Security Exception while getting input stream for $packageName", e)
                return TRANSPORT_ERROR
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Security Exception while getting input stream for $packageName", e)
                return TRANSPORT_ERROR
            } catch (e: UnsupportedVersionException) {
                Log.e(TAG, "Backup data for $packageName uses unsupported version ${e.version}.", e)
                return TRANSPORT_PACKAGE_REJECTED
            }
        }

        return outputFactory.getOutputStream(pfd).use { outputStream ->
            try {
                copyInputStream(outputStream)
            } catch (e: IOException) {
                Log.w(TAG, "Error copying stream for package $packageName.", e)
                return TRANSPORT_PACKAGE_REJECTED
            }
        }
    }

    @Throws(IOException::class)
    private fun copyInputStream(outputStream: OutputStream): Int {
        val state = this.state ?: throw IllegalStateException("no state")
        val inputStream = state.inputStream ?: throw IllegalStateException("no stream")

        if (state.version == 0.toByte()) {
            // read segment from input stream and decrypt it
            val decrypted = try {
                @Suppress("deprecation")
                crypto.decryptSegment(inputStream)
            } catch (e: EOFException) {
                Log.i(TAG, "   EOF")
                // close input stream here as we won't need it anymore
                closeQuietly(inputStream)
                return NO_MORE_DATA
            }

            // write decrypted segment to output stream (without header)
            outputStream.write(decrypted)
            // return number of written bytes
            return decrypted.size
        } else {
            val buffer = ByteArray(MAX_SEGMENT_LENGTH)
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) {
                Log.i(TAG, "   EOF")
                // close input stream here as we won't need it anymore
                closeQuietly(inputStream)
                return NO_MORE_DATA
            }
            outputStream.write(buffer, 0, bytesRead)
            return bytesRead
        }
    }

    /**
     * If the OS encounters an error while processing full data for restore,
     * it will invoke this method
     * to tell the transport that it should abandon the data download for the current package.
     *
     * @return [TRANSPORT_OK] if the transport shut down the current stream cleanly,
     * or [TRANSPORT_ERROR] to indicate a serious transport-level failure.
     * If the transport reports an error here,
     * the entire restore operation will immediately be finished
     * with no further attempts to restore app data.
     */
    fun abortFullRestore(): Int {
        val state = this.state ?: throw IllegalStateException("no state")
        Log.i(TAG, "Abort full restore of ${state.packageInfo.packageName}!")

        resetState()
        return TRANSPORT_OK
    }

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    fun finishRestore() {
        val state = this.state ?: throw IllegalStateException("no state")
        Log.i(TAG, "Finish restore of ${state.packageInfo.packageName}!")

        resetState()
    }

    private fun resetState() {
        Log.i(TAG, "Resetting state.")

        closeQuietly(state?.inputStream)
        state = null
    }

}
