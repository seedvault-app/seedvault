package com.stevesoltys.seedvault.transport.restore

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.InputStream

interface KVRestorePlugin {

    /**
     * Return true if there is data stored for the given package.
     */
    @Throws(IOException::class)
    suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean

    /**
     * Return all record keys for the given token and package.
     *
     * Note: Implementations usually expect that you call [hasDataForPackage]
     *       with the same parameters before.
     *
     * For file-based plugins, this is usually a list of file names in the package directory.
     */
    @Throws(IOException::class)
    suspend fun listRecords(token: Long, packageInfo: PackageInfo): List<String>

    /**
     * Return an [InputStream] for the given token, package and key
     * which will provide the record's encrypted value.
     *
     * Note: Implementations might expect that you call [hasDataForPackage] before.
     */
    @Throws(IOException::class)
    suspend fun getInputStreamForRecord(token: Long, packageInfo: PackageInfo, key: String): InputStream

}
