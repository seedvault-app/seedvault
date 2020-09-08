package com.stevesoltys.seedvault.transport.restore

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.InputStream

interface FullRestorePlugin {

    /**
     * Return true if there is data stored for the given package.
     */
    @Throws(IOException::class)
    suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean

    @Throws(IOException::class)
    suspend fun getInputStreamForPackage(token: Long, packageInfo: PackageInfo): InputStream

}
