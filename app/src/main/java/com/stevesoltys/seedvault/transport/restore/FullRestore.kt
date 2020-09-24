package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import libcore.io.IoUtils.closeQuietly
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

private class FullRestoreState(
    val token: Long,
    val packageInfo: PackageInfo
) {
    var inputStream: InputStream? = null
}

private val TAG = FullRestore::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class FullRestore(
    private val plugin: FullRestorePlugin,
    private val outputFactory: OutputFactory,
    private val headerReader: HeaderReader,
    private val crypto: Crypto
) {

    private var state: FullRestoreState? = null

    fun hasState() = state != null

    /**
     * Return true if there is data stored for the given package.
     */
    @Throws(IOException::class)
    suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return plugin.hasDataForPackage(token, packageInfo)
    }

    /**
     * This prepares to restore the given package from the given restore token.
     *
     * It is possible that the system decides to not restore the package.
     * Then a new state will be initialized right away without calling other methods.
     */
    fun initializeState(token: Long, packageInfo: PackageInfo) {
        state = FullRestoreState(token, packageInfo)
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
    suspend fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int {
        val state = this.state ?: throw IllegalStateException("no state")
        val packageName = state.packageInfo.packageName

        if (state.inputStream == null) {
            Log.i(TAG, "First Chunk, initializing package input stream.")
            try {
                val inputStream = plugin.getInputStreamForPackage(state.token, state.packageInfo)
                val version = headerReader.readVersion(inputStream)
                crypto.decryptHeader(inputStream, version, packageName)
                state.inputStream = inputStream
            } catch (e: IOException) {
                Log.w(TAG, "Error getting input stream for $packageName", e)
                return TRANSPORT_PACKAGE_REJECTED
            } catch (e: SecurityException) {
                Log.e(TAG, "Security Exception while getting input stream for $packageName", e)
                return TRANSPORT_ERROR
            } catch (e: UnsupportedVersionException) {
                Log.e(TAG, "Backup data for $packageName uses unsupported version ${e.version}.", e)
                return TRANSPORT_PACKAGE_REJECTED
            }
        }

        return readInputStream(socket)
    }

    private fun readInputStream(socket: ParcelFileDescriptor): Int = socket.use { fileDescriptor ->
        val state = this.state ?: throw IllegalStateException("no state")
        val packageName = state.packageInfo.packageName
        val inputStream = state.inputStream ?: throw IllegalStateException("no stream")
        val outputStream = outputFactory.getOutputStream(fileDescriptor)

        try {
            // read segment from input stream and decrypt it
            val decrypted = try {
                // TODO handle IOException
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
        } catch (e: IOException) {
            Log.w(TAG, "Error processing stream for package $packageName.", e)
            closeQuietly(inputStream)
            return TRANSPORT_PACKAGE_REJECTED
        } finally {
            closeQuietly(outputStream)
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
