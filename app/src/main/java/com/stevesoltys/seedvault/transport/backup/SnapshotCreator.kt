/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Apk
import com.stevesoltys.seedvault.proto.Snapshot.App
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.settings.SettingsManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString

internal class SnapshotCreatorFactory(
    private val context: Context,
    private val clock: Clock,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
) {
    fun createSnapshotCreator() =
        SnapshotCreator(context, clock, packageService, settingsManager, metadataManager)
}

internal class SnapshotCreator(
    private val context: Context,
    private val clock: Clock,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
) {

    private val log = KotlinLogging.logger { }
    private val snapshotBuilder = Snapshot.newBuilder()
    private val appBuilderMap = mutableMapOf<String, App.Builder>()
    private val blobsMap = mutableMapOf<String, Blob>()

    private val launchableSystemApps by lazy {
        packageService.launchableSystemApps.map { it.activityInfo.packageName }.toSet()
    }

    fun onApkBackedUp(
        packageInfo: PackageInfo,
        apk: Apk,
        blobMap: Map<String, Blob>,
    ) {
        appBuilderMap.getOrPut(packageInfo.packageName) {
            App.newBuilder()
        }.apply {
            val label = packageInfo.applicationInfo?.loadLabel(context.packageManager)
            if (label != null) name = label.toString()
            setApk(apk)
        }
        blobsMap.putAll(blobMap)
    }

    fun onPackageBackedUp(
        packageInfo: PackageInfo,
        backupType: BackupType,
        backupData: BackupData,
    ) {
        val packageName = packageInfo.packageName
        val isSystemApp = packageInfo.isSystemApp()
        val chunkIds = backupData.chunkIds.forProto()
        appBuilderMap.getOrPut(packageName) {
            App.newBuilder()
        }.apply {
            time = clock.time()
            state = APK_AND_DATA.name // TODO review those states and their usefulness for snapshot
            type = backupType.forSnapshot()
            val label = packageInfo.applicationInfo?.loadLabel(context.packageManager)
            if (label != null) name = label.toString()
            system = isSystemApp
            launchableSystemApp = isSystemApp && launchableSystemApps.contains(packageName)
            addAllChunkIds(chunkIds)
        }
        blobsMap.putAll(backupData.blobMap)
        metadataManager.onPackageBackedUp(packageInfo, backupType, backupData.size)
    }

    fun onIconsBackedUp(backupData: BackupData) {
        snapshotBuilder.addAllIconChunkIds(backupData.chunkIds.forProto())
        blobsMap.putAll(backupData.blobMap)
    }

    fun finalizeSnapshot(): Snapshot {
        log.info { "finalizeSnapshot()" }
        val userName = getUserName()
        val deviceName = if (userName == null) {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL} - $userName"
        }

        @SuppressLint("HardwareIds")
        val snapshot = snapshotBuilder.apply {
            version = VERSION.toInt()
            token = clock.time()
            name = deviceName
            androidId = Settings.Secure.getString(context.contentResolver, ANDROID_ID)
            sdkInt = Build.VERSION.SDK_INT
            androidIncremental = Build.VERSION.INCREMENTAL
            d2D = settingsManager.d2dBackupsEnabled()
            putAllApps(appBuilderMap.mapValues { it.value.build() })
            putAllBlobs(this@SnapshotCreator.blobsMap)
        }.build()
        appBuilderMap.clear()
        snapshotBuilder.clear()
        return snapshot
    }

    private fun getUserName(): String? {
        val perm = "android.permission.QUERY_USERS"
        return if (context.checkSelfPermission(perm) == PERMISSION_GRANTED) {
            val userManager = context.getSystemService(UserManager::class.java) ?: return null
            userManager.userName
        } else null
    }

    private fun BackupType.forSnapshot(): Snapshot.BackupType = when (this) {
        BackupType.KV -> Snapshot.BackupType.KV
        BackupType.FULL -> Snapshot.BackupType.FULL
    }

}

fun Iterable<String>.forProto() = map { ByteString.fromHex(it) }
fun Iterable<ByteString>.hexFromProto() = map { it.toByteArray().toHexString() }
fun ByteString.hexFromProto() = toByteArray().toHexString()
fun Snapshot.getBlobHandles(repoId: String, chunkIds: List<String>) = chunkIds.map { chunkId ->
    val blobId = blobsMap[chunkId]?.id?.hexFromProto()
        ?: error("Blob for $chunkId missing from snapshot $token")
    AppBackupFileType.Blob(repoId, blobId)
}
