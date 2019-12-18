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
                    // create new default metadata
                    // Attention: If this happens due to a read error, we will overwrite remote metadata
                    Log.w(TAG, "Creating new metadata...")
                    BackupMetadata(token = clock.time())
                }
            }
            return field
        }

    /**
     * Call this when initializing a new device.
     *
     * A new backup token will be generated.
     * Existing [BackupMetadata] will be cleared
     * and written encrypted to the given [OutputStream] as well as the internal cache.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onDeviceInitialization(metadataOutputStream: OutputStream) {
        metadata = BackupMetadata(token = clock.time())
        metadataWriter.write(metadata, metadataOutputStream)
        writeMetadataToCache()
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
        if (metadata.packageMetadata.containsKey(packageName)) {
            metadata.packageMetadata[packageName]?.time = now
        } else {
            metadata.packageMetadata[packageName] = PackageMetadata(time = now)
        }
        try {
            metadataWriter.write(metadata, metadataOutputStream)
        } catch (e: IOException) {
            Log.w(TAG, "Error writing metadata to storage", e)
            // revert metadata and do not write it to cache
            metadata = oldMetadata
            throw IOException(e)
        }
        writeMetadataToCache()
    }

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
            return null
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
