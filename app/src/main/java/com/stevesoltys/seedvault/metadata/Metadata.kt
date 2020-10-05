package com.stevesoltys.seedvault.metadata

import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.os.Build
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import java.io.InputStream

typealias PackageMetadataMap = HashMap<String, PackageMetadata>

data class BackupMetadata(
    internal val version: Byte = VERSION,
    internal val token: Long,
    internal var time: Long = 0L,
    internal val androidVersion: Int = Build.VERSION.SDK_INT,
    internal val androidIncremental: String = Build.VERSION.INCREMENTAL,
    internal val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    internal val packageMetadataMap: PackageMetadataMap = PackageMetadataMap()
)

internal const val JSON_METADATA = "@meta@"
internal const val JSON_METADATA_VERSION = "version"
internal const val JSON_METADATA_TOKEN = "token"
internal const val JSON_METADATA_TIME = "time"
internal const val JSON_METADATA_SDK_INT = "sdk_int"
internal const val JSON_METADATA_INCREMENTAL = "incremental"
internal const val JSON_METADATA_NAME = "name"

enum class PackageState {
    /**
     * Both, the APK and the package's data was backed up.
     * This is the expected state of all user-installed packages.
     */
    APK_AND_DATA,

    /**
     * Package data could not get backed up, because the app exceeded the allowed quota.
     */
    QUOTA_EXCEEDED,

    /**
     * Package data could not get backed up, because the app reported no data to back up.
     */
    NO_DATA,

    /**
     * Package data could not get backed up, because the app has [FLAG_STOPPED].
     */
    WAS_STOPPED,

    /**
     * Package data could not get backed up, because it was not allowed.
     * Most often, this is a manifest opt-out, but it could also be a disabled or system-user app.
     */
    NOT_ALLOWED,

    /**
     * Package data could not get backed up, because an error occurred during backup.
     */
    UNKNOWN_ERROR,
}

data class PackageMetadata(
    /**
     * The timestamp in milliseconds of the last app data backup.
     * It is 0 if there never was a data backup.
     */
    internal var time: Long = 0L,
    internal var state: PackageState = UNKNOWN_ERROR,
    internal val system: Boolean = false,
    internal val version: Long? = null,
    internal val installer: String? = null,
    internal val splits: List<ApkSplit>? = null,
    internal val sha256: String? = null,
    internal val signatures: List<String>? = null
) {
    fun hasApk(): Boolean {
        return version != null && sha256 != null && signatures != null
    }
}

data class ApkSplit(
    val name: String,
    val sha256: String
    // There's also a revisionCode, but it doesn't seem to be used just yet
)

internal const val JSON_PACKAGE_TIME = "time"
internal const val JSON_PACKAGE_STATE = "state"
internal const val JSON_PACKAGE_SYSTEM = "system"
internal const val JSON_PACKAGE_VERSION = "version"
internal const val JSON_PACKAGE_INSTALLER = "installer"
internal const val JSON_PACKAGE_SPLITS = "splits"
internal const val JSON_PACKAGE_SPLIT_NAME = "name"
internal const val JSON_PACKAGE_SHA256 = "sha256"
internal const val JSON_PACKAGE_SIGNATURES = "signatures"

internal class DecryptionFailedException(cause: Throwable) : Exception(cause)

class EncryptedBackupMetadata private constructor(
    val token: Long,
    val inputStream: InputStream?,
    val error: Boolean
) {

    constructor(token: Long, inputStream: InputStream) : this(token, inputStream, false)

    /**
     * Indicates that there was an error retrieving the encrypted backup metadata.
     */
    constructor(token: Long) : this(token, null, true)
}
