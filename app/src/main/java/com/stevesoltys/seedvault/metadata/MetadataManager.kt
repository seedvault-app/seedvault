/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
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
    private val metadataWriter: MetadataWriter,
    private val metadataReader: MetadataReader,
) {

    private val uninitializedMetadata = BackupMetadata(token = -42L, salt = "foo bar")
    private var metadata: BackupMetadata = uninitializedMetadata
        get() {
            if (field == uninitializedMetadata) {
                field = try {
                    val m = getMetadataFromCache() ?: throw IOException()
                    if (m == uninitializedMetadata) m.copy(salt = "initialized")
                    else m
                } catch (e: IOException) {
                    // This can happen if the storage location ran out of space
                    // or the app process got killed while writing the file.
                    // It is hard to recover from this, so we try as best as we can here:
                    Log.e(TAG, "ERROR getting metadata cache, creating new file ", e)
                    uninitializedMetadata.copy(salt = "initialized")
                }
            }
            return field
        }

    /**
     * Call this after a package has been backed up successfully.
     *
     * It updates the packages' metadata.
     */
    @Synchronized
    @Throws(IOException::class)
    fun onPackageBackedUp(
        packageInfo: PackageInfo,
        type: BackupType?,
        size: Long?,
    ) {
        val packageName = packageInfo.packageName
        modifyCachedMetadata {
            val now = clock.time()
            metadata.packageMetadataMap.getOrPut(packageName) {
                PackageMetadata(
                    time = now,
                    state = APK_AND_DATA,
                    backupType = type,
                    size = size,
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
        backupType: BackupType? = null,
    ) {
        check(packageState != APK_AND_DATA) { "Backup Error with non-error package state." }
        modifyCachedMetadata {
            metadata.packageMetadataMap.getOrPut(packageInfo.packageName) {
                PackageMetadata(
                    time = 0L,
                    state = packageState,
                    backupType = backupType,
                    name = packageInfo.applicationInfo?.loadLabel(context.packageManager),
                )
            }.state = packageState
        }
    }

    /**
     * Call this for all packages we can not back up for some reason.
     *
     * It updates the packages' local metadata.
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
                name = packageInfo.applicationInfo?.loadLabel(context.packageManager),
            )
        }.apply {
            state = packageState
            // update name, if none was set, yet (can happen while migrating to storing names)
            if (this.name == null) {
                this.name = packageInfo.applicationInfo?.loadLabel(context.packageManager)
            }
        }
    }

    @Synchronized
    fun getPackageMetadata(packageName: String): PackageMetadata? {
        return metadata.packageMetadataMap[packageName]?.copy()
    }

    @Throws(IOException::class)
    private fun modifyCachedMetadata(modFun: () -> Unit) {
        val oldMetadata = metadata.copy(
            // copy map, otherwise it will re-use same reference
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
