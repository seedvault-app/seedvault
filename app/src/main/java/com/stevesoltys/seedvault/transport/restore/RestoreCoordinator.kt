/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

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
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.DecryptionFailedException
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.MetadataReader
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.repo.getBlobHandles
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.D2D_TRANSPORT_FLAGS
import com.stevesoltys.seedvault.transport.DEFAULT_TRANSPORT_FLAGS
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import java.io.IOException
import java.security.GeneralSecurityException

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
    val backup: RestorableBackup,
) {
    var currentPackage: String? = null
}

private val TAG = RestoreCoordinator::class.java.simpleName

internal class RestoreCoordinator(
    private val context: Context,
    private val crypto: Crypto,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
    private val notificationManager: BackupNotificationManager,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
    private val kv: KVRestore,
    private val full: FullRestore,
    private val metadataReader: MetadataReader,
) {

    private val backend: Backend get() = backendManager.backend
    private var state: RestoreCoordinatorState? = null
    private var restorableBackup: RestorableBackup? = null
    private val failedPackages = ArrayList<String>()

    suspend fun getAvailableBackups(): RestorableBackupResult {
        Log.i(TAG, "getAvailableBackups")
        val fileHandles = try {
            backend.getAvailableBackupFileHandles()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available backups.", e)
            return RestorableBackupResult.ErrorResult(e)
        }
        val backups = ArrayList<RestorableBackup>()
        var lastException: Exception? = null
        for (handle in fileHandles) {
            try {
                val backup = when (handle) {
                    is AppBackupFileType.Snapshot -> RestorableBackup(
                        repoId = handle.repoId,
                        snapshot = snapshotManager.loadSnapshot(handle),
                    )
                    is LegacyAppBackupFile.Metadata -> {
                        val metadata = backend.load(handle).use { inputStream ->
                            metadataReader.readMetadata(inputStream, handle.token)
                        }
                        RestorableBackup(backupMetadata = metadata)
                    }
                    else -> error("Unexpected file handle: $handle")
                }
                backups.add(backup)
            } catch (e: IOException) {
                Log.e(TAG, "Error while getting restore set $handle", e)
                lastException = e
                continue
            } catch (e: SecurityException) {
                Log.e(TAG, "Error while getting restore set $handle", e)
                return RestorableBackupResult.ErrorResult(e)
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "General security error while decrypting restore set $handle", e)
                lastException = e
                continue
            } catch (e: DecryptionFailedException) {
                Log.e(TAG, "Error while decrypting restore set $handle", e)
                lastException = e
                continue
            } catch (e: UnsupportedVersionException) {
                Log.w(TAG, "Backup with unsupported version read", e)
                lastException = e
                continue
            }
        }
        if (backups.isEmpty()) return RestorableBackupResult.ErrorResult(lastException)
        return RestorableBackupResult.SuccessResult(backups)
    }

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableRestoreSets(): Array<RestoreSet>? {
        Log.d(TAG, "getAvailableRestoreSets")
        val result = getAvailableBackups() as? RestorableBackupResult.SuccessResult ?: return null
        val backups = result.backups
        return backups.map { backup ->
            val transportFlags = if (backup.d2dBackup) {
                D2D_TRANSPORT_FLAGS
            } else {
                DEFAULT_TRANSPORT_FLAGS
            }
            val deviceName = if (backup.d2dBackup) {
                D2D_DEVICE_NAME
            } else {
                backup.deviceName
            }
            RestoreSet(backup.deviceName, deviceName, backup.token, transportFlags)
        }.toTypedArray()
    }

    /**
     * Get the identifying token of the backup set currently being stored from this device.
     * This is used in the case of applications wishing to restore their last-known-good data.
     *
     * @return A token that can be used for restore,
     * or 0 if there is no backup set available corresponding to the current device state.
     */
    fun getCurrentRestoreSet(): Long {
        val token = settingsManager.token ?: 0L
        Log.d(TAG, "getCurrentRestoreSet() = $token")
        return token
    }

    /**
     * Call this before starting the restore as an optimization to prevent re-fetching metadata.
     */
    fun beforeStartRestore(restorableBackup: RestorableBackup) {
        this.restorableBackup = restorableBackup
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
        Log.i(TAG, "Start restore $token with ${packages.map { info -> info.packageName }}")

        // If there's only one package to restore (Auto Restore feature), add it to the state
        val autoRestorePackageInfo =
            if (packages.size == 2 && packages[0].packageName == MAGIC_PACKAGE_MANAGER) {
                val pmPackageName = packages[1].packageName
                Log.d(TAG, "Optimize for single package restore of $pmPackageName")
                // check if the backup is on removable storage that is not plugged in
                if (isStorageRemovableAndNotAvailable()) {
                    // check if we even have a backup of that app
                    if (metadataManager.getPackageMetadata(pmPackageName) != null) {
                        // remind user to plug in storage device
                        val storageName = backendManager.backendProperties?.name
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

        val backup = if (restorableBackup?.token == token) {
            restorableBackup!! // if token matches, backupMetadata is non-null
        } else {
            if (autoRestorePackageInfo == null) { // no auto-restore
                Log.e(TAG, "No cached backups, loading all and look for $token")
                val backup = getAvailableBackups() as? RestorableBackupResult.SuccessResult
                    ?: return TRANSPORT_ERROR
                backup.backups.find { it.token == token } ?: return TRANSPORT_ERROR
            } else {
                // this is auto-restore, so we use cache and try hard to find a working restore set
                Log.i(TAG, "No cached backups, loading all and look for $token")
                val backups = try {
                    snapshotManager.loadCachedSnapshots().map { snapshot ->
                        RestorableBackup(crypto.repoId, snapshot)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading cached snapshots: ", e)
                    (getAvailableBackups() as? RestorableBackupResult.SuccessResult)?.backups
                        ?: return TRANSPORT_ERROR
                }
                Log.i(TAG, "Found ${backups.size} snapshots.")
                val autoRestorePackageName = autoRestorePackageInfo.packageName
                val sortedBackups = backups.sortedByDescending { it.token } // latest first
                sortedBackups.find { it.token == token } ?: sortedBackups.find {
                    val chunkIds = it.packageMetadataMap[autoRestorePackageName]?.chunkIds
                    // try a backup where our auto restore package has data
                    !chunkIds.isNullOrEmpty()
                } ?: return TRANSPORT_ERROR
            }
        }
        state = RestoreCoordinatorState(token, packages.iterator(), autoRestorePackageInfo, backup)
        restorableBackup = null
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
        val state = this.state ?: throw IllegalStateException("no state")

        if (!state.packages.hasNext()) return NO_MORE_PACKAGES
        val packageInfo = state.packages.next()
        Log.i(TAG, "nextRestorePackage() => ${packageInfo.packageName}")
        val version = state.backup.version
        if (version == 0.toByte()) return nextRestorePackageV0(state, packageInfo)
        if (version == 1.toByte()) return nextRestorePackageV1(state, packageInfo)

        val packageName = packageInfo.packageName
        val repoId = state.backup.repoId ?: error("No repoId in v2 backup")
        val snapshot = state.backup.snapshot ?: error("No snapshot in v2 backup")
        val type = when (state.backup.packageMetadataMap[packageName]?.backupType) {
            BackupType.KV -> {
                val blobHandles = try {
                    val chunkIds = state.backup.packageMetadataMap[packageName]?.chunkIds
                        ?: error("no metadata or chunkIds")
                    snapshot.getBlobHandles(repoId, chunkIds)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting blob handles: ", e)
                    failedPackages.add(packageName)
                    // abort here as this is close to an assertion error
                    return null
                }
                kv.initializeState(
                    version = version,
                    packageInfo = packageInfo,
                    blobHandles = blobHandles,
                    autoRestorePackageInfo = state.autoRestorePackageInfo,
                )
                state.currentPackage = packageName
                TYPE_KEY_VALUE
            }
            BackupType.FULL -> {
                val blobHandles = try {
                    val chunkIds = state.backup.packageMetadataMap[packageName]?.chunkIds
                        ?: error("no metadata or chunkIds")
                    snapshot.getBlobHandles(repoId, chunkIds)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting blob handles: ", e)
                    failedPackages.add(packageName)
                    // abort here as this is close to an assertion error
                    return null
                }
                full.initializeState(version, packageInfo, blobHandles)
                state.currentPackage = packageName
                TYPE_FULL_STREAM
            }
            null -> {
                Log.i(TAG, "No backup type found for $packageName. Skipping...")
                state.backup.packageMetadataMap[packageName]?.backupType?.let { s ->
                    Log.w(TAG, "State was ${s.name}")
                }
                failedPackages.add(packageName)
                // don't return null and cause abort here, but try next package
                return nextRestorePackage()
            }
        }
        return RestoreDescription(packageName, type)
    }

    @Suppress("deprecation")
    private suspend fun nextRestorePackageV1(
        state: RestoreCoordinatorState,
        packageInfo: PackageInfo,
    ): RestoreDescription? {
        val packageName = packageInfo.packageName
        val type = when (state.backup.packageMetadataMap[packageName]?.backupType) {
            BackupType.KV -> {
                kv.initializeStateV1(
                    token = state.token,
                    name = crypto.getNameForPackage(state.backup.salt, packageName),
                    packageInfo = packageInfo,
                    autoRestorePackageInfo = state.autoRestorePackageInfo,
                )
                state.currentPackage = packageName
                TYPE_KEY_VALUE
            }
            BackupType.FULL -> {
                val name = crypto.getNameForPackage(state.backup.salt, packageName)
                full.initializeStateV1(state.token, name, packageInfo)
                state.currentPackage = packageName
                TYPE_FULL_STREAM
            }
            null -> {
                Log.i(TAG, "No backup type found for $packageName. Skipping...")
                state.backup.packageMetadataMap[packageName]?.backupType?.let { s ->
                    Log.w(TAG, "State was ${s.name}")
                }
                failedPackages.add(packageName)
                // don't return null and cause abort here, but try next package
                return nextRestorePackage()
            }
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
                    kv.initializeStateV0(state.token, packageInfo)
                    state.currentPackage = packageName
                    TYPE_KEY_VALUE
                }
                full.hasDataForPackage(state.token, packageInfo) -> {
                    Log.i(TAG, "Found full backup data for $packageName.")
                    full.initializeStateV0(state.token, packageInfo)
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
        Log.d(TAG, "getRestoreData()")
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
        if (full.hasState) full.finishRestore()
        state = null
    }

    fun isFailedPackage(packageName: String) = packageName in failedPackages

    private fun isStorageRemovableAndNotAvailable(): Boolean {
        val storage = backendManager.backendProperties ?: return false
        return storage.isUnavailableUsb(context)
    }

}
