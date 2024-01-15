package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.NO_MORE_PACKAGES
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.app.backup.RestoreDescription.TYPE_KEY_VALUE
import android.app.backup.RestoreSet
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.DecryptionFailedException
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.D2D_TRANSPORT_FLAGS
import com.stevesoltys.seedvault.transport.DEFAULT_TRANSPORT_FLAGS
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import java.io.IOException

/**
 * Device name used in AOSP to indicate that a restore set is part of a device-to-device migration.
 * See getBackupEligibilityRules in frameworks/base/services/backup/java/com/android/server/
 * backup/restore/ActiveRestoreSession.java. AOSP currently relies on this constant, and it is not
 * publicly exposed. Framework code indicates they intend to use a flag, instead, in the future.
 */
internal const val D2D_DEVICE_NAME = "D2D"

private data class RestoreCoordinatorState(
    val token: Long,
    val packages: Iterator<PackageInfo>,
    /**
     * Optional [PackageInfo] for single package restore, to reduce data needed to read for @pm@
     */
    val autoRestorePackageInfo: PackageInfo?,
    val backupMetadata: BackupMetadata,
) {
    var currentPackage: String? = null
}

private val TAG = RestoreCoordinator::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class RestoreCoordinator(
    private val context: Context,
    private val crypto: Crypto,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
    private val notificationManager: BackupNotificationManager,
    private val plugin: StoragePlugin,
    private val kv: KVRestore,
    private val full: FullRestore,
    private val metadataReader: MetadataReader,
) {

    private var state: RestoreCoordinatorState? = null
    private var backupMetadata: BackupMetadata? = null
    private val failedPackages = ArrayList<String>()

    suspend fun getAvailableMetadata(): Map<Long, BackupMetadata>? {
        val availableBackups = plugin.getAvailableBackups() ?: return null
        val metadataMap = HashMap<Long, BackupMetadata>()
        for (encryptedMetadata in availableBackups) {
            try {
                val metadata = encryptedMetadata.inputStreamRetriever().use { inputStream ->
                    metadataReader.readMetadata(inputStream, encryptedMetadata.token)
                }
                metadataMap[encryptedMetadata.token] = metadata
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
            }
        }
        Log.i(TAG, "Got available metadata for tokens: ${metadataMap.keys}")
        return metadataMap
    }

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableRestoreSets(): Array<RestoreSet>? {
        return getAvailableMetadata()?.map { (_, metadata) ->

            val transportFlags = if (metadata.d2dBackup) {
                D2D_TRANSPORT_FLAGS
            } else {
                DEFAULT_TRANSPORT_FLAGS
            }

            val deviceName = if (metadata.d2dBackup) {
                D2D_DEVICE_NAME
            } else {
                metadata.deviceName
            }

            RestoreSet(metadata.deviceName, deviceName, metadata.token, transportFlags)
        }?.toTypedArray()
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
     * Call this before starting the restore as an optimization to prevent re-fetching metadata.
     */
    fun beforeStartRestore(backupMetadata: BackupMetadata) {
        this.backupMetadata = backupMetadata

        if (backupMetadata.d2dBackup) {
            settingsManager.setD2dBackupsEnabled(true)
        }
    }

    /**
     * Start restoring application data from backup.
     * After calling this function,
     * there will be alternate calls to [nextRestorePackage] and [getRestoreData]
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by [getAvailableRestoreSets]
     * or [getCurrentRestoreSet].
     * @param packages List of applications to restore (if data is available).
     * Application data will be restored in the order given.
     * @return One of [TRANSPORT_OK] (OK so far, call [nextRestorePackage])
     * or [TRANSPORT_ERROR] (an error occurred, the restore should be aborted and rescheduled).
     */
    suspend fun startRestore(token: Long, packages: Array<out PackageInfo>): Int {
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

        val metadata = if (backupMetadata?.token == token) {
            backupMetadata!! // if token matches, backupMetadata is non-null
        } else {
            getAvailableMetadata()?.get(token) ?: return TRANSPORT_ERROR
        }
        state = RestoreCoordinatorState(token, packages.iterator(), pmPackageInfo, metadata)
        backupMetadata = null
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
        val version = state.backupMetadata.version
        if (version == 0.toByte()) return nextRestorePackageV0(state, packageInfo)

        val packageName = packageInfo.packageName
        val type = try {
            when (state.backupMetadata.packageMetadataMap[packageName]?.backupType) {
                BackupType.KV -> {
                    val name = crypto.getNameForPackage(state.backupMetadata.salt, packageName)
                    if (plugin.hasData(state.token, name)) {
                        Log.i(TAG, "Found K/V data for $packageName.")
                        kv.initializeState(
                            version = version,
                            token = state.token,
                            name = name,
                            packageInfo = packageInfo,
                            autoRestorePackageInfo = state.autoRestorePackageInfo
                        )
                        state.currentPackage = packageName
                        TYPE_KEY_VALUE
                    } else throw IOException("No data found for $packageName. Skipping.")
                }

                BackupType.FULL -> {
                    val name = crypto.getNameForPackage(state.backupMetadata.salt, packageName)
                    if (plugin.hasData(state.token, name)) {
                        Log.i(TAG, "Found full backup data for $packageName.")
                        full.initializeState(version, state.token, name, packageInfo)
                        state.currentPackage = packageName
                        TYPE_FULL_STREAM
                    } else throw IOException("No data found for $packageName. Skipping...")
                }

                null -> {
                    Log.i(TAG, "No backup type found for $packageName. Skipping...")
                    state.backupMetadata.packageMetadataMap[packageName]?.backupType?.let { s ->
                        Log.w(TAG, "State was ${s.name}")
                    }
                    failedPackages.add(packageName)
                    return nextRestorePackage()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error finding restore data for $packageName.", e)
            failedPackages.add(packageName)
            // don't return null and cause abort here, but try next package
            return nextRestorePackage()
        }
        return RestoreDescription(packageName, type)
    }

    @Suppress("deprecation")
    private suspend fun nextRestorePackageV0(
        state: RestoreCoordinatorState,
        packageInfo: PackageInfo,
    ): RestoreDescription? {
        val packageName = packageInfo.packageName
        val type = try {
            when {
                // check key/value data first and if available, don't even check for full data
                kv.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found K/V data for $packageName.")
                    kv.initializeState(0x00, state.token, "", packageInfo, null)
                    state.currentPackage = packageName
                    TYPE_KEY_VALUE
                }

                full.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found full backup data for $packageName.")
                    full.initializeState(0x00, state.token, "", packageInfo)
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
            // don't return null and cause abort here, but try next package
            return nextRestorePackage()
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

    fun isFailedPackage(packageName: String) = packageName in failedPackages

    // TODO this is plugin specific, needs to be factored out when supporting different plugins
    private fun isStorageRemovableAndNotAvailable(): Boolean {
        val storage = settingsManager.getStorage() ?: return false
        return storage.isUnavailableUsb(context)
    }

}
