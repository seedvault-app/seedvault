/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import android.content.pm.ApplicationInfo.FLAG_STOPPED
import android.os.Build
import com.stevesoltys.seedvault.crypto.TYPE_METADATA
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.repo.hexFromProto
import com.stevesoltys.seedvault.worker.BASE_SPLIT
import org.calyxos.backup.storage.crypto.StreamCrypto.toByteArray
import java.nio.ByteBuffer

typealias PackageMetadataMap = HashMap<String, PackageMetadata>

data class BackupMetadata(
    internal val version: Byte = VERSION,
    internal val token: Long,
    internal val salt: String,
    internal var time: Long = 0L,
    internal val androidVersion: Int = Build.VERSION.SDK_INT,
    internal val androidIncremental: String = Build.VERSION.INCREMENTAL,
    internal val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    internal var d2dBackup: Boolean = false,
    internal val packageMetadataMap: PackageMetadataMap = PackageMetadataMap(),
) {

    companion object {
        fun fromSnapshot(s: Snapshot) = BackupMetadata(
            version = s.version.toByte(),
            token = s.token,
            salt = "",
            time = s.token,
            androidVersion = s.sdkInt,
            androidIncremental = s.androidIncremental,
            deviceName = "${s.name} - ${s.user}",
            d2dBackup = s.d2D,
            packageMetadataMap = s.appsMap.mapValues { (_, app) ->
                PackageMetadata.fromSnapshot(app)
            } as PackageMetadataMap
        )
    }

    val size: Long
        get() = packageMetadataMap.values.sumOf { m ->
            (m.size ?: 0L) + (m.splits?.sumOf { it.size ?: 0L } ?: 0L)
        }
}

internal const val JSON_METADATA = "@meta@"
internal const val JSON_METADATA_VERSION = "version"
internal const val JSON_METADATA_TOKEN = "token"
internal const val JSON_METADATA_SALT = "salt"
internal const val JSON_METADATA_TIME = "time"
internal const val JSON_METADATA_SDK_INT = "sdk_int"
internal const val JSON_METADATA_INCREMENTAL = "incremental"
internal const val JSON_METADATA_NAME = "name"
internal const val JSON_METADATA_D2D_BACKUP = "d2d_backup"

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
    internal var backupType: BackupType? = null,
    internal var size: Long? = null,
    internal var name: CharSequence? = null,
    internal val system: Boolean = false,
    internal val isLaunchableSystemApp: Boolean = false,
    internal val version: Long? = null,
    internal val installer: String? = null,
    internal val splits: List<ApkSplit>? = null,
    internal val baseApkChunkIds: List<String>? = null, // used for v2
    internal val chunkIds: List<String>? = null, // used for v2
    internal val sha256: String? = null,
    internal val signatures: List<String>? = null,
) {

    companion object {
        fun fromSnapshot(app: Snapshot.App) = PackageMetadata(
            time = app.time,
            backupType = app.type.toBackupType(),
            name = app.name,
            chunkIds = app.chunkIdsList.hexFromProto(),
            system = app.system,
            isLaunchableSystemApp = app.launchableSystemApp,
            version = app.apk.versionCode,
            installer = app.apk.installer.takeIf { it.isNotEmpty() },
            baseApkChunkIds = run {
                val baseChunk = app.apk.splitsList.find { it.name == BASE_SPLIT }
                if (baseChunk == null || baseChunk.chunkIdsCount == 0) {
                    null
                } else {
                    baseChunk.chunkIdsList.hexFromProto()
                }
            },
            splits = app.apk.splitsList.filter { it.name != BASE_SPLIT }.map {
                ApkSplit(
                    name = it.name,
                    size = null,
                    sha256 = "",
                    chunkIds = if (it.chunkIdsCount == 0) null else it.chunkIdsList.hexFromProto()
                )
            }.takeIf { it.isNotEmpty() }, // expected null if there are no splits
            sha256 = null,
            signatures = app.apk.signaturesList.map { it.toByteArray().encodeBase64() }.takeIf {
                it.isNotEmpty()
            },
        )

        fun Snapshot.BackupType.toBackupType() = when (this) {
            Snapshot.BackupType.FULL -> BackupType.FULL
            Snapshot.BackupType.KV -> BackupType.KV
            else -> null
        }
    }

    val isInternalSystem: Boolean = system && !isLaunchableSystemApp
    fun hasApk(): Boolean {
        return version != null && // v2 doesn't use sha256 here
            (sha256 != null || baseApkChunkIds?.isNotEmpty() == true) &&
            signatures != null
    }
}

data class ApkSplit(
    val name: String,
    val size: Long?,
    val sha256: String,
    val chunkIds: List<String>? = null, // used for v2
    // There's also a revisionCode, but it doesn't seem to be used just yet
)

enum class BackupType { KV, FULL }

internal const val JSON_PACKAGE_TIME = "time"
internal const val JSON_PACKAGE_BACKUP_TYPE = "backupType"
internal const val JSON_PACKAGE_STATE = "state"
internal const val JSON_PACKAGE_SIZE = "size"
internal const val JSON_PACKAGE_APP_NAME = "name"
internal const val JSON_PACKAGE_SYSTEM = "system"
internal const val JSON_PACKAGE_SYSTEM_LAUNCHER = "systemLauncher"
internal const val JSON_PACKAGE_VERSION = "version"
internal const val JSON_PACKAGE_INSTALLER = "installer"
internal const val JSON_PACKAGE_SPLITS = "splits"
internal const val JSON_PACKAGE_SPLIT_NAME = "name"
internal const val JSON_PACKAGE_SHA256 = "sha256"
internal const val JSON_PACKAGE_SIGNATURES = "signatures"

internal class DecryptionFailedException(cause: Throwable) : Exception(cause)

internal fun getAD(version: Byte, token: Long) = ByteBuffer.allocate(2 + 8)
    .put(version)
    .put(TYPE_METADATA)
    .put(token.toByteArray())
    .array()
