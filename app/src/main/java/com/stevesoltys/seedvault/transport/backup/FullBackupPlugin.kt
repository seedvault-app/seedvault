package com.stevesoltys.seedvault.transport.backup

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface FullBackupPlugin {

    fun getQuota(): Long

    // TODO consider using a salted hash for the package name to not leak it to the storage server
    @Throws(IOException::class)
    suspend fun getOutputStream(targetPackage: PackageInfo): OutputStream

    /**
     * Remove all data associated with the given package.
     */
    @Throws(IOException::class)
    suspend fun removeDataOfPackage(packageInfo: PackageInfo)

}
