package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.Clock
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

private val TAG = MetadataManager::class.java.simpleName
@VisibleForTesting
internal const val METADATA_CACHE_FILE = "metadata.cache"

@WorkerThread
class MetadataManager(
        private val context: Context,
        private val clock: Clock,
        private val metadataWriter: MetadataWriter,
        private val metadataReader: MetadataReader) {

    private val uninitializedMetadata = BackupMetadata(token = 0L)
    private var metadata: BackupMetadata = uninitializedMetadata
        get() {
            if (field == uninitializedMetadata) {
                field = try {
                    getMetadataFromCache() ?: throw IOException()
                } catch (e: IOException) {
                    // If this happens, it is hard to recover from this. Let's hope it never does.
                    throw AssertionError("Error reading metadata from cache", e)
                }
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
        metadata = BackupMetadata(token = token)
        metadataWriter.write(metadata, metadataOutputStream)
        writeMetadataToCache()
    }

    /**
     * Call this after an APK as been successfully written to backup storage.
     * It will update the package's metadata, but NOT write it storage or internal cache.
     * You still need to call [onPackageBackedUp] afterwards to write it out.
     */
    @Synchronized
    fun onApkBackedUp(packageName: String, packageMetadata: PackageMetadata) {
        metadata.packageMetadataMap[packageName]?.let {
            check(it.time <= packageMetadata.time) {
                "APK backup set time of $packageName backwards"
            }
            check(packageMetadata.version != null) {
                "APK backup returned version null"
            }
            check(it.version == null || it.version < packageMetadata.version) {
                "APK backup backed up the same or a smaller version: was ${it.version} is ${packageMetadata.version}"
            }
        }
        metadata.packageMetadataMap[packageName] = packageMetadata
    }

    /**
     * Call this after a package has been backed up successfully.
     *
     * It updates the packages' metadata
     * and writes it encrypted to the given [OutputStream] as well as the internal cache.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onPackageBackedUp(packageName: String, metadataOutputStream: OutputStream) {
        val oldMetadata = metadata.copy()
        val now = clock.time()
        metadata.time = now
        if (metadata.packageMetadataMap.containsKey(packageName)) {
            metadata.packageMetadataMap[packageName]?.time = now
        } else {
            metadata.packageMetadataMap[packageName] = PackageMetadata(time = now)
        }
        try {
            metadataWriter.write(metadata, metadataOutputStream)
        } catch (e: IOException) {
            Log.w(TAG, "Error writing metadata to storage", e)
            // revert metadata and do not write it to cache
            // TODO also revert changes made by last [onApkBackedUp]
            metadata = oldMetadata
            throw IOException(e)
        }
        writeMetadataToCache()
    }

    /**
     * Returns the current backup token.
     *
     * If the token is 0L, it is not yet initialized and must not be used for anything.
     */
    @Synchronized
    fun getBackupToken(): Long = metadata.token

    /**
     * Returns the last backup time in unix epoch milli seconds.
     *
     * Note that this might be a blocking I/O call.
     */
    @Synchronized
    fun getLastBackupTime(): Long = metadata.time

    @Synchronized
    fun getPackageMetadata(packageName: String): PackageMetadata? {
        return metadata.packageMetadataMap[packageName]?.copy()
    }

    @Synchronized
    @VisibleForTesting
    private fun getMetadataFromCache(): BackupMetadata? {
        try {
            with(context.openFileInput(METADATA_CACHE_FILE)) {
                return metadataReader.decode(readBytes())
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
        with(context.openFileOutput(METADATA_CACHE_FILE, MODE_PRIVATE)) {
            write(metadataWriter.encode(metadata))
        }
    }

}
