package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.IBackupManager
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.NO_MORE_PACKAGES
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.app.backup.RestoreDescription.TYPE_KEY_VALUE
import android.app.backup.RestoreSet
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.collection.LongSparseArray
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.DecryptionFailedException
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import libcore.io.IoUtils.closeQuietly
import java.io.IOException

private data class RestoreCoordinatorState(
    val token: Long,
    val packages: Iterator<PackageInfo>,
    /**
     * Optional [PackageInfo] for single package restore, to reduce data needed to read for @pm@
     */
    val pmPackageInfo: PackageInfo?
) {
    var currentPackage: String? = null
}

private val TAG = RestoreCoordinator::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class RestoreCoordinator(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
    private val notificationManager: BackupNotificationManager,
    private val plugin: RestorePlugin,
    private val kv: KVRestore,
    private val full: FullRestore,
    private val metadataReader: MetadataReader
) {

    private var state: RestoreCoordinatorState? = null
    private var backupMetadata: LongSparseArray<BackupMetadata>? = null
    private val failedPackages = ArrayList<String>()

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableRestoreSets(): Array<RestoreSet>? {
        val availableBackups = plugin.getAvailableBackups() ?: return null
        val restoreSets = ArrayList<RestoreSet>()
        val metadataMap = LongSparseArray<BackupMetadata>()
        for (encryptedMetadata in availableBackups) {
            if (encryptedMetadata.error) continue
            check(encryptedMetadata.inputStream != null) {
                "No error when getting encrypted metadata, but stream is still missing."
            }
            try {
                val metadata = metadataReader.readMetadata(
                    encryptedMetadata.inputStream,
                    encryptedMetadata.token
                )
                metadataMap.put(encryptedMetadata.token, metadata)
                val set = RestoreSet(metadata.deviceName, metadata.deviceName, metadata.token)
                restoreSets.add(set)
            } catch (e: IOException) {
                Log.e(TAG, "Error while getting restore set ${encryptedMetadata.token}", e)
                continue
            } catch (e: SecurityException) {
                Log.e(TAG, "Error while getting restore set ${encryptedMetadata.token}", e)
                return null
            } catch (e: DecryptionFailedException) {
                Log.e(TAG, "Error while decrypting restore set ${encryptedMetadata.token}", e)
                continue
            } catch (e: UnsupportedVersionException) {
                Log.w(TAG, "Backup with unsupported version read", e)
                continue
            } finally {
                closeQuietly(encryptedMetadata.inputStream)
            }
        }
        Log.i(TAG, "Got available restore sets: $restoreSets")
        this.backupMetadata = metadataMap
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
        return (settingsManager.getToken() ?: 0L).apply {
            Log.i(TAG, "Got current restore set token: $this")
        }
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
        check(state == null) { "Started new restore with existing state: $state" }
        Log.i(TAG, "Start restore with ${packages.map { info -> info.packageName }}")

        // If there's only one package to restore (Auto Restore feature), add it to the state
        val pmPackageInfo =
            if (packages.size == 2 && packages[0].packageName == MAGIC_PACKAGE_MANAGER) {
                val pmPackageName = packages[1].packageName
                Log.d(TAG, "Optimize for single package restore of $pmPackageName")
                // check if the backup is on removable storage that is not plugged in
                if (isStorageRemovableAndNotAvailable()) {
                    // check if we even have a backup of that app
                    if (metadataManager.getPackageMetadata(pmPackageName) != null) {
                        // remind user to plug in storage device
                        val storageName = settingsManager.getStorage()?.name
                            ?: context.getString(R.string.settings_backup_location_none)
                        notificationManager.onRemovableStorageNotAvailableForRestore(
                            pmPackageName,
                            storageName
                        )
                    }
                    return TRANSPORT_ERROR
                }
                packages[1]
            } else null

        state = RestoreCoordinatorState(token, packages.iterator(), pmPackageInfo)
        failedPackages.clear()
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
    suspend fun nextRestorePackage(): RestoreDescription? {
        Log.i(TAG, "Next restore package!")
        val state = this.state ?: throw IllegalStateException("no state")

        if (!state.packages.hasNext()) return NO_MORE_PACKAGES
        val packageInfo = state.packages.next()
        val packageName = packageInfo.packageName

        val type = try {
            when {
                // check key/value data first and if available, don't even check for full data
                kv.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found K/V data for $packageName.")
                    kv.initializeState(state.token, packageInfo, state.pmPackageInfo)
                    state.currentPackage = packageName
                    TYPE_KEY_VALUE
                }
                full.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found full backup data for $packageName.")
                    full.initializeState(state.token, packageInfo)
                    state.currentPackage = packageName
                    TYPE_FULL_STREAM
                }
                else -> {
                    Log.i(TAG, "No data found for $packageName. Skipping.")
                    return nextRestorePackage()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error finding restore data for $packageName.", e)
            failedPackages.add(packageName)
            return null
        }
        return RestoreDescription(packageName, type)
    }

    /**
     * Get the data for the application returned by [nextRestorePackage],
     * if that method reported [TYPE_KEY_VALUE] as its delivery type.
     * If the package has only [TYPE_FULL_STREAM] data, then this method will return an error.
     *
     * @param data An open, writable file into which the key/value backup data should be stored.
     * @return the same error codes as [startRestore].
     */
    suspend fun getRestoreData(data: ParcelFileDescriptor): Int {
        return kv.getRestoreData(data).apply {
            if (this != TRANSPORT_OK) {
                // add current package to failed ones
                state?.currentPackage?.let { failedPackages.add(it) }
            }
        }
    }

    /**
     * Ask the transport to provide data for the "current" package being restored.
     *
     * After this method returns zero, the system will then call [nextRestorePackage]
     * to begin the restore process for the next application, and the sequence begins again.
     */
    suspend fun getNextFullRestoreDataChunk(outputFileDescriptor: ParcelFileDescriptor): Int {
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
        Log.d(TAG, "abortFullRestore")
        state?.currentPackage?.let { failedPackages.add(it) }
        return full.abortFullRestore()
    }

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    fun finishRestore() {
        Log.d(TAG, "finishRestore")
        if (full.hasState()) full.finishRestore()
        state = null
    }

    /**
     * Call this after calling [IBackupManager.getAvailableRestoreTokenForUser]
     * to retrieve additional [BackupMetadata] that is not available in [RestoreSet].
     *
     * It will also clear the saved metadata, so that subsequent calls will return null.
     */
    fun getAndClearBackupMetadata(): LongSparseArray<BackupMetadata>? {
        val result = backupMetadata
        backupMetadata = null
        return result
    }

    fun isFailedPackage(packageName: String) = packageName in failedPackages

    // TODO this is plugin specific, needs to be factored out when supporting different plugins
    private fun isStorageRemovableAndNotAvailable(): Boolean {
        val storage = settingsManager.getStorage() ?: return false
        return storage.isUnavailableUsb(context)
    }

}
