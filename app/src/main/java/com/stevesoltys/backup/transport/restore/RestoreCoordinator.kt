package com.stevesoltys.backup.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.*
import android.app.backup.RestoreSet
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.backup.header.UnsupportedVersionException
import com.stevesoltys.backup.metadata.DecryptionFailedException
import com.stevesoltys.backup.metadata.MetadataReader
import com.stevesoltys.backup.settings.SettingsManager
import libcore.io.IoUtils.closeQuietly
import java.io.IOException

private class RestoreCoordinatorState(
        internal val token: Long,
        internal val packages: Iterator<PackageInfo>)

private val TAG = RestoreCoordinator::class.java.simpleName

internal class RestoreCoordinator(
        private val settingsManager: SettingsManager,
        private val plugin: RestorePlugin,
        private val kv: KVRestore,
        private val full: FullRestore,
        private val metadataReader: MetadataReader) {

    private var state: RestoreCoordinatorState? = null

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    fun getAvailableRestoreSets(): Array<RestoreSet>? {
        val availableBackups = plugin.getAvailableBackups() ?: return null
        val restoreSets = ArrayList<RestoreSet>()
        for (encryptedMetadata in availableBackups) {
            if (encryptedMetadata.error) continue
            check(encryptedMetadata.inputStream != null)  // if there's no error, there must be a stream
            try {
                val metadata = metadataReader.readMetadata(encryptedMetadata.inputStream, encryptedMetadata.token)
                val set = RestoreSet(metadata.deviceName, metadata.deviceName, metadata.token)
                restoreSets.add(set)
            } catch (e: IOException) {
                Log.e(TAG, "Error while getting restore sets", e)
                return null
            } catch (e: SecurityException) {
                Log.e(TAG, "Error while getting restore sets", e)
                return null
            } catch (e: DecryptionFailedException) {
                Log.e(TAG, "Error while decrypting restore set", e)
                continue
            } catch (e: UnsupportedVersionException) {
                Log.w(TAG, "Backup with unsupported version read", e)
                continue
            } finally {
                closeQuietly(encryptedMetadata.inputStream)
            }
        }
        Log.i(TAG, "Got available restore sets: $restoreSets")
        return restoreSets.toTypedArray()
    }

    /**
     * Get the identifying token of the backup set currently being stored from this device.
     * This is used in the case of applications wishing to restore their last-known-good data.
     *
     * @return A token that can be used for restore,
     * or 0 if there is no backup set available corresponding to the current device state.
     */
    fun getCurrentRestoreSet(): Long {
        return settingsManager.getBackupToken()
                .apply { Log.i(TAG, "Got current restore set token: $this") }
    }

    /**
     * Start restoring application data from backup.
     * After calling this function,
     * there will be alternate calls to [nextRestorePackage] and [getRestoreData]
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by [getAvailableRestoreSets] or [getCurrentRestoreSet].
     * @param packages List of applications to restore (if data is available).
     * Application data will be restored in the order given.
     * @return One of [TRANSPORT_OK] (OK so far, call [nextRestorePackage])
     * or [TRANSPORT_ERROR] (an error occurred, the restore should be aborted and rescheduled).
     */
    fun startRestore(token: Long, packages: Array<out PackageInfo>): Int {
        check(state == null)
        Log.i(TAG, "Start restore with ${packages.map { info -> info.packageName }}")
        state = RestoreCoordinatorState(token, packages.iterator())
        return TRANSPORT_OK
    }

    /**
     * Get the package name of the next package with data in the backup store,
     * plus a description of the structure of the restored archive:
     * either [TYPE_KEY_VALUE] for an original-API key/value dataset,
     * or [TYPE_FULL_STREAM] for a tarball-type archive stream.
     *
     * If the package name in the returned [RestoreDescription] object is [NO_MORE_PACKAGES],
     * it indicates that no further data is available in the current restore session,
     * i.e. all packages described in [startRestore] have been processed.
     *
     * If this method returns null, it means that a transport-level error has
     * occurred and the entire restore operation should be abandoned.
     *
     * The OS may call [nextRestorePackage] multiple times
     * before calling either [getRestoreData] or [getNextFullRestoreDataChunk].
     * It does this when it has determined
     * that it needs to skip restore of one or more packages.
     * The transport should not actually transfer any restore data
     * for the given package in response to [nextRestorePackage],
     * but rather wait for an explicit request before doing so.
     *
     * @return A [RestoreDescription] object containing the name of one of the packages
     * supplied to [startRestore] plus an indicator of the data type of that restore data;
     * or [NO_MORE_PACKAGES] to indicate that no more packages can be restored in this session;
     * or null to indicate a transport-level error.
     */
    fun nextRestorePackage(): RestoreDescription? {
        Log.i(TAG, "Next restore package!")
        val state = this.state ?: throw IllegalStateException()

        if (!state.packages.hasNext()) return NO_MORE_PACKAGES
        val packageInfo = state.packages.next()
        val packageName = packageInfo.packageName

        val type = try {
            when {
                // check key/value data first and if available, don't even check for full data
                kv.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found K/V data for $packageName.")
                    kv.initializeState(state.token, packageInfo)
                    TYPE_KEY_VALUE
                }
                full.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found full backup data for $packageName.")
                    full.initializeState(state.token, packageInfo)
                    TYPE_FULL_STREAM
                }
                else -> {
                    Log.i(TAG, "No data found for $packageName. Skipping.")
                    return nextRestorePackage()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error finding restore data for $packageName.", e)
            return null
        }
        return RestoreDescription(packageName, type)
    }

    /**
     * Get the data for the application returned by [nextRestorePackage],
     * if that method reported [TYPE_KEY_VALUE] as its delivery type.
     * If the package has only TYPE_FULL_STREAM data, then this method will return an error.
     *
     * @param data An open, writable file into which the key/value backup data should be stored.
     * @return the same error codes as [startRestore].
     */
    fun getRestoreData(data: ParcelFileDescriptor): Int {
        return kv.getRestoreData(data)
    }

    /**
     * Ask the transport to provide data for the "current" package being restored.
     *
     * After this method returns zero, the system will then call [nextRestorePackage]
     * to begin the restore process for the next application, and the sequence begins again.
     */
    fun getNextFullRestoreDataChunk(outputFileDescriptor: ParcelFileDescriptor): Int {
        return full.getNextFullRestoreDataChunk(outputFileDescriptor)
    }

    /**
     * If the OS encounters an error while processing full data for restore, it will abort.
     *
     * The OS will then either call [nextRestorePackage] again to move on
     * to restoring the next package in the set being iterated over,
     * or will call [finishRestore] to shut down the restore operation.
     */
    fun abortFullRestore(): Int {
        return full.abortFullRestore()
    }

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    fun finishRestore() {
        if (full.hasState()) full.finishRestore()
    }

}
