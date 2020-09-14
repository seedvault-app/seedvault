package com.stevesoltys.seedvault.transport.backup

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface KVBackupPlugin {

    /**
     * Get quota for key/value backups.
     */
    fun getQuota(): Long

    // TODO consider using a salted hash for the package name (and key) to not leak it to the storage server
    /**
     * Return true if there are records stored for the given package.
     * This is always called first per [PackageInfo], before subsequent methods.
     *
     * Independent of the return value, the storage should now be prepared to store K/V pairs.
     * E.g. file-based plugins should a create a directory for the package, if none exists.
     */
    @Throws(IOException::class)
    suspend fun hasDataForPackage(packageInfo: PackageInfo): Boolean

    /**
     * Return an [OutputStream] for the given package and key
     * which will receive the record's encrypted value.
     */
    @Throws(IOException::class)
    suspend fun getOutputStreamForRecord(packageInfo: PackageInfo, key: String): OutputStream

    /**
     * Delete the record for the given package identified by the given key.
     */
    @Throws(IOException::class)
    suspend fun deleteRecord(packageInfo: PackageInfo, key: String)

    /**
     * Remove all data associated with the given package,
     * but be prepared to receive new records afterwards with [getOutputStreamForRecord].
     */
    @Throws(IOException::class)
    suspend fun removeDataOfPackage(packageInfo: PackageInfo)

    /**
     * The package finished backup.
     * This can be an opportunity to clear existing caches or to do other clean-up work.
     */
    fun packageFinished(packageInfo: PackageInfo)

}
