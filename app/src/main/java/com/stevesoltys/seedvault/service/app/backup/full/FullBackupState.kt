package com.stevesoltys.seedvault.service.app.backup.full

import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream

class FullBackupState(
    val packageInfo: PackageInfo,
    val inputFileDescriptor: ParcelFileDescriptor,
    val inputStream: InputStream,
    var outputStreamInit: (suspend () -> OutputStream)?,
) {
    /**
     * This is an encrypted stream that can be written to directly.
     */
    var outputStream: OutputStream? = null
    val packageName: String = packageInfo.packageName
    var size: Long = 0
}
