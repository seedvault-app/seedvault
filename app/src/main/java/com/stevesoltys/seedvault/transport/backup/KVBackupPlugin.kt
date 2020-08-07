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
     */
    @Throws(IOException::class)
    fun hasDataForPackage(packageInfo: PackageInfo): Boolean

    /**
     * This marks the beginning of a backup operation.
     *
     * Make sure that there is a place to store K/V pairs for the given package.
     * E.g. file-based plugins should a create a directory for the package, if none exists.
     */
    @Throws(IOException::class)
    suspend fun ensureRecordStorageForPackage(packageInfo: PackageInfo)

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
    fun deleteRecord(packageInfo: PackageInfo, key: String)

    /**
     * Remove all data associated with the given package.
     */
    @Throws(IOException::class)
    fun removeDataOfPackage(packageInfo: PackageInfo)

}
