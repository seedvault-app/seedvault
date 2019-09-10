package com.stevesoltys.backup.metadata

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import com.stevesoltys.backup.header.VERSION
import com.stevesoltys.backup.transport.DEFAULT_RESTORE_SET_TOKEN

data class BackupMetadata(
        internal val version: Byte = VERSION,
        internal val token: Long = DEFAULT_RESTORE_SET_TOKEN,
        internal val androidVersion: Int = SDK_INT,
        internal val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"
)

internal const val JSON_VERSION = "version"
internal const val JSON_TOKEN = "token"
internal const val JSON_ANDROID_VERSION = "androidVersion"
internal const val JSON_DEVICE_NAME = "deviceName"

class FormatException(cause: Throwable) : Exception(cause)
