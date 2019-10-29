package com.stevesoltys.seedvault.metadata

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import com.stevesoltys.seedvault.header.VERSION
import java.io.InputStream

data class BackupMetadata(
        internal val version: Byte = VERSION,
        internal val token: Long,
        internal val androidVersion: Int = SDK_INT,
        internal val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"
)

internal const val JSON_VERSION = "version"
internal const val JSON_TOKEN = "token"
internal const val JSON_ANDROID_VERSION = "androidVersion"
internal const val JSON_DEVICE_NAME = "deviceName"

class DecryptionFailedException(cause: Throwable) : Exception(cause)

class EncryptedBackupMetadata private constructor(val token: Long, val inputStream: InputStream?, val error: Boolean) {
    constructor(token: Long, inputStream: InputStream) : this(token, inputStream, false)
    /**
     * Indicates that there was an error retrieving the encrypted backup metadata.
     */
    constructor(token: Long) : this(token, null, true)
}
