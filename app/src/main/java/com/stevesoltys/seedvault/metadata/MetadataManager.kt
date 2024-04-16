package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.UserManager
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
import com.stevesoltys.seedvault.settings.SettingsManager
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
    private val settingsManager: SettingsManager,
) {

    private val uninitializedMetadata = BackupMetadata(token = 0L, salt = "")
    private var metadata: BackupMetadata = uninitializedMetadata
        get() {
            if (field == uninitializedMetadata) {
                field = try {
                    getMetadataFromCache() ?: throw IOException()
                } catch (e: IOException) {
                    // This can happen if the storage location ran out of space
                    // or the app process got killed while writing the file.
                    // It is hard to recover from this, so we try as best as we can here:
                    Log.e(TAG, "ERROR getting metadata cache, creating new file ", e)
                    // This should cause requiresInit() return true
                    uninitializedMetadata.copy(version = (-1).toByte())
                }
                mLastBackupTime.postValue(field.time)
            }
            return field
        }

    /**
     * Call this when initializing a new device.
     *
     * Existing [BackupMetadata] will be cleared
     * and new metadata with the given [token] will be written to the internal cache
     * with a fresh salt.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onDeviceInitialization(token: Long) {
        val salt = crypto.getRandomBytes(METADATA_SALT_SIZE).encodeBase64()
        modifyCachedMetadata {
            val userName = getUserName()
            metadata = BackupMetadata(
                token = token,
                salt = salt,
                deviceName = if (userName == null) {
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                } else {
                    "${Build.MANUFACTURER} ${Build.MODEL} - $userName"
                },
            )
        }
    }

    /**
     * Call this after a package's APK has been backed up successfully.
     *
     * It updates the packages' metadata to the internal cache.
     * You still need to call [uploadMetadata] to persist all local modifications.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onApkBackedUp(
        packageInfo: PackageInfo,
        packageMetadata: PackageMetadata,
    ) {
        val packageName = packageInfo.packageName
        metadata.packageMetadataMap[packageName]?.let {
            check(packageMetadata.version != null) {
                "APK backup returned version null"
            }
        }
        val oldPackageMetadata = metadata.packageMetadataMap[packageName]
            ?: PackageMetadata()
        modifyCachedMetadata {
            metadata.packageMetadataMap[packageName] = oldPackageMetadata.copy(
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
        size: Long?,
        metadataOutputStream: OutputStream,
    ) {
        val packageName = packageInfo.packageName
        modifyMetadata(metadataOutputStream) {
            val now = clock.time()
            metadata.time = now
            metadata.d2dBackup = settingsManager.d2dBackupsEnabled()
            metadata.packageMetadataMap.getOrPut(packageName) {
                PackageMetadata(
                    time = now,
                    state = APK_AND_DATA,
                    backupType = type,
                    size = size,
                    system = packageInfo.isSystemApp(),
                )
            }.apply {
                time = now
                state = APK_AND_DATA
                backupType = type
                // don't override a previous K/V size, if there were no K/V changes
                if (size != null) this.size = size
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
        modifyMetadata(metadataOutputStream) {
            metadata.packageMetadataMap.getOrPut(packageInfo.packageName) {
                PackageMetadata(
                    time = 0L,
                    state = packageState,
                    backupType = backupType,
                    system = packageInfo.isSystemApp()
                )
            }.state = packageState
        }
    }

    /**
     * Call this for all packages we can not back up for some reason.
     *
     * It updates the packages' local metadata.
     * You still need to call [uploadMetadata] to persist all local modifications.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun onPackageDoesNotGetBackedUp(
        packageInfo: PackageInfo,
        packageState: PackageState,
    ) = modifyCachedMetadata {
        metadata.packageMetadataMap.getOrPut(packageInfo.packageName) {
            PackageMetadata(
                time = 0L,
                state = packageState,
                system = packageInfo.isSystemApp(),
            )
        }.state = packageState
    }

    /**
     * Uploads metadata to given [metadataOutputStream] after performing local modifications.
     */
    @Synchronized
    @Throws(IOException::class)
    fun uploadMetadata(metadataOutputStream: OutputStream) {
        metadataWriter.write(metadata, metadataOutputStream)
    }

    @Throws(IOException::class)
    private fun modifyCachedMetadata(modFun: () -> Unit) {
        val oldMetadata = metadata.copy( // copy map, otherwise it will re-use same reference
            packageMetadataMap = PackageMetadataMap(metadata.packageMetadataMap),
        )
        try {
            modFun.invoke()
            writeMetadataToCache()
        } catch (e: IOException) {
            Log.w(TAG, "Error writing metadata to storage", e)
            // revert metadata and do not write it to cache
            metadata = oldMetadata
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    private fun modifyMetadata(metadataOutputStream: OutputStream, modFun: () -> Unit) {
        val oldMetadata = metadata.copy( // copy map, otherwise it will re-use same reference
            packageMetadataMap = PackageMetadataMap(metadata.packageMetadataMap),
        )
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
    fun getPackagesBackupSize(): Long {
        return metadata.packageMetadataMap.values.sumOf { it.size ?: 0L }
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

    private fun getUserName(): String? {
        val perm = "android.permission.QUERY_USERS"
        return if (context.checkSelfPermission(perm) == PERMISSION_GRANTED) {
            val userManager = context.getSystemService(UserManager::class.java) ?: return null
            userManager.userName
        } else null
    }

}
