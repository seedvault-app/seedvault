package com.stevesoltys.backup.transport.backup

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface FullBackupPlugin {

    fun getQuota(): Long

    @Throws(IOException::class)
    fun getOutputStream(targetPackage: PackageInfo): OutputStream

    @Throws(IOException::class)
    fun cancelFullBackup(targetPackage: PackageInfo)

}
