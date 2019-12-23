package com.stevesoltys.seedvault.transport.restore

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.InputStream

interface KVRestorePlugin {

    /**
     * Return true if there is data stored for the given package.
     */
    @Throws(IOException::class)
    fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean

    /**
     * Return all record keys for the given token and package.
     *
     * For file-based plugins, this is usually a list of file names in the package directory.
     */
    @Throws(IOException::class)
    fun listRecords(token: Long, packageInfo: PackageInfo): List<String>

    /**
     * Return an [InputStream] for the given token, package and key
     * which will provide the record's encrypted value.
     */
    @Throws(IOException::class)
    fun getInputStreamForRecord(token: Long, packageInfo: PackageInfo, key: String): InputStream

}
