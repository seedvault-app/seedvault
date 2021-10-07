package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

private val TAG = MetadataManager::class.java.simpleName

@VisibleForTesting
internal const val METADATA_CACHE_FILE = "metadata.cache"
internal const val METADATA_SALT_SIZE = 32

@WorkerThread
internal class MetadataManager(
    private val context: Context,
    private val clock: Clock,
    private val crypto: Crypto,
    private val metadataWriter: MetadataWriter,
    private val metadataReader: MetadataReader,
) {

    private val uninitializedMetadata = BackupMetadata(token = 0L, salt = "")
    private var metadata: BackupMetadata = uninitializedMetadata
        get() {
            if (field == uninitializedMetadata) {
                field = try {
                    getMetadataFromCache() ?: throw IOException()
                } catch (e: IOException) {
                    // If this happens, it is hard to recover from this. Let's hope it never does.
                    throw AssertionError("Error reading metadata from cache", e)
                }
                mLastBackupTime.postValue(field.time)
            }
            return field
        }

    /**
     * Call this when initializing a new device.
     *
     * Existing [BackupMetadata] will be cleared, use the given new token,
     * and written encrypted to the given [OutputStream] as well as the internal cache.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onDeviceInitialization(token: Long, metadataOutputStream: OutputStream) {
        val salt = crypto.getRandomBytes(METADATA_SALT_SIZE).encodeBase64()
        modifyMetadata(metadataOutputStream) {
            metadata = BackupMetadata(token = token, salt = salt)
        }
    }

    /**
     * Call this after a package's APK has been backed up successfully.
     *
     * It updates the packages' metadata
     * and writes it encrypted to the given [OutputStream] as well as the internal cache.
     *
     * Closing the [OutputStream] is the responsibility of the caller.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onApkBackedUp(
        packageInfo: PackageInfo,
        packageMetadata: PackageMetadata,
        metadataOutputStream: OutputStream,
    ) {
        val packageName = packageInfo.packageName
        metadata.packageMetadataMap[packageName]?.let {
            check(packageMetadata.version != null) {
                "APK backup returned version null"
            }
            check(it.version == null || it.version < packageMetadata.version) {
                "APK backup backed up the same or a smaller version:" +
                    "was ${it.version} is ${packageMetadata.version}"
            }
        }
        val oldPackageMetadata = metadata.packageMetadataMap[packageName]
            ?: PackageMetadata()
        // only allow state change if backup of this package is not allowed,
        // because we need to change from the default of UNKNOWN_ERROR here,
        // but otherwise don't want to modify the state since set elsewhere.
        val newState =
            if (packageMetadata.state == NOT_ALLOWED || packageMetadata.state == WAS_STOPPED) {
                packageMetadata.state
            } else {
                oldPackageMetadata.state
            }
        modifyMetadata(metadataOutputStream) {
            metadata.packageMetadataMap[packageName] = oldPackageMetadata.copy(
                state = newState,
                system = packageInfo.isSystemApp(),
                version = packageMetadata.version,
                installer = packageMetadata.installer,
                splits = packageMetadata.splits,
                sha256 = packageMetadata.sha256,
                signatures = packageMetadata.signatures
            )
        }
    }

    /**
     * Call this after a package has been backed up successfully.
     *
     * It updates the packages' metadata
     * and writes it encrypted to the given [OutputStream] as well as the internal cache.
     *
     * Closing the [OutputStream] is the responsibility of the caller.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onPackageBackedUp(
        packageInfo: PackageInfo,
        type: BackupType,
        metadataOutputStream: OutputStream,
    ) {
        val packageName = packageInfo.packageName
        modifyMetadata(metadataOutputStream) {
            val now = clock.time()
            metadata.time = now
            if (metadata.packageMetadataMap.containsKey(packageName)) {
                metadata.packageMetadataMap[packageName]!!.time = now
                metadata.packageMetadataMap[packageName]!!.state = APK_AND_DATA
                metadata.packageMetadataMap[packageName]!!.backupType = type
            } else {
                metadata.packageMetadataMap[packageName] = PackageMetadata(
                    time = now,
                    state = APK_AND_DATA,
                    backupType = type,
                    system = packageInfo.isSystemApp()
                )
            }
        }
    }

    /**
     * Call this after a package data backup failed.
     *
     * It updates the packages' metadata
     * and writes it encrypted to the given [OutputStream] as well as the internal cache.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun onPackageBackupError(
        packageInfo: PackageInfo,
        packageState: PackageState,
        metadataOutputStream: OutputStream,
        backupType: BackupType? = null,
    ) {
        check(packageState != APK_AND_DATA) { "Backup Error with non-error package state." }
        val packageName = packageInfo.packageName
        modifyMetadata(metadataOutputStream) {
            if (metadata.packageMetadataMap.containsKey(packageName)) {
                metadata.packageMetadataMap[packageName]!!.state = packageState
            } else {
                metadata.packageMetadataMap[packageName] = PackageMetadata(
                    time = 0L,
                    state = packageState,
                    backupType = backupType,
                    system = packageInfo.isSystemApp()
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun modifyMetadata(metadataOutputStream: OutputStream, modFun: () -> Unit) {
        val oldMetadata = metadata.copy()
        try {
            modFun.invoke()
            metadataWriter.write(metadata, metadataOutputStream)
            writeMetadataToCache()
        } catch (e: IOException) {
            Log.w(TAG, "Error writing metadata to storage", e)
            // revert metadata and do not write it to cache
            metadata = oldMetadata
            throw IOException(e)
        }
        mLastBackupTime.postValue(metadata.time)
    }

    /**
     * Returns the current backup token.
     *
     * If the token is 0L, it is not yet initialized and must not be used for anything.
     */
    @Synchronized
    @Deprecated(
        "Responsibility for current token moved to SettingsManager",
        ReplaceWith("settingsManager.getToken()")
    )
    fun getBackupToken(): Long = metadata.token

    /**
     * Returns the last backup time in unix epoch milli seconds.
     *
     * Note that this might be a blocking I/O call.
     */
    @Synchronized
    fun getLastBackupTime(): Long = mLastBackupTime.value ?: metadata.time

    private val mLastBackupTime = MutableLiveData<Long>()
    internal val lastBackupTime: LiveData<Long> = mLastBackupTime.distinctUntilChanged()

    internal val salt: String
        @Synchronized get() = metadata.salt

    internal val requiresInit: Boolean
        @Synchronized get() = metadata == uninitializedMetadata || metadata.version < VERSION

    @Synchronized
    fun getPackageMetadata(packageName: String): PackageMetadata? {
        return metadata.packageMetadataMap[packageName]?.copy()
    }

    @Synchronized
    fun getPackagesNumBackedUp(): Int {
        // FIXME we are under-reporting packages here,
        //  because we have no way to also include upgraded system apps
        return metadata.packageMetadataMap.filter { (_, packageMetadata) ->
            !packageMetadata.system && ( // ignore system apps
                packageMetadata.state == APK_AND_DATA || // either full success
                    packageMetadata.state == NO_DATA // or apps that simply had no data
                )
        }.count()
    }

    @Synchronized
    @VisibleForTesting
    private fun getMetadataFromCache(): BackupMetadata? {
        try {
            context.openFileInput(METADATA_CACHE_FILE).use { stream ->
                return metadataReader.decode(stream.readBytes())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error parsing cached metadata", e)
            return null
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "Cached metadata not found, creating...")
            return uninitializedMetadata
        }
    }

    @Synchronized
    @VisibleForTesting
    @Throws(IOException::class)
    private fun writeMetadataToCache() {
        context.openFileOutput(METADATA_CACHE_FILE, MODE_PRIVATE).use { stream ->
            stream.write(metadataWriter.encode(metadata))
        }
    }

}
