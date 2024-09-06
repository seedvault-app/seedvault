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
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Apk
import com.stevesoltys.seedvault.proto.Snapshot.App
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.settings.SettingsManager
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString

internal class SnapshotCreatorFactory(
    private val context: Context,
    private val clock: Clock,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
) {
    fun createSnapshotCreator() = SnapshotCreator(context, clock, packageService, settingsManager)
}

internal class SnapshotCreator(
    private val context: Context,
    private val clock: Clock,
    private val packageService: PackageService,
    private val settingsManager: SettingsManager,
) {

    private val snapshotBuilder = Snapshot.newBuilder()
        .setToken(clock.time())
    private val appBuilderMap = mutableMapOf<String, App.Builder>()
    private val blobsMap = mutableMapOf<String, Blob>()

    private val launchableSystemApps by lazy {
        packageService.launchableSystemApps.map { it.activityInfo.packageName }.toSet()
    }

    fun onApkBackedUp(
        packageName: String,
        apk: Apk,
        chunkMap: Map<String, Blob>,
    ) {
        val appBuilder = appBuilderMap.getOrPut(packageName) {
            App.newBuilder()
        }
        appBuilder.setApk(apk)
        blobsMap.putAll(chunkMap)
    }

    fun onPackageBackedUp(
        packageInfo: PackageInfo,
        type: BackupType,
        backupData: BackupData,
    ) {
        val packageName = packageInfo.packageName
        val builder = appBuilderMap.getOrPut(packageName) {
            App.newBuilder()
        }
        val isSystemApp = packageInfo.isSystemApp()
        val chunkIds = backupData.chunks.forProto()
        blobsMap.putAll(backupData.chunkMap)
        builder
            .setTime(clock.time())
            .setState(APK_AND_DATA.name)
            .setType(type.forSnapshot())
            .setName(packageInfo.applicationInfo?.loadLabel(context.packageManager)?.toString())
            .setSystem(isSystemApp)
            .setLaunchableSystemApp(isSystemApp && launchableSystemApps.contains(packageName))
            .addAllChunkIds(chunkIds)
    }

    fun onIconsBackedUp(backupData: BackupData) {
        snapshotBuilder.addAllIconChunkIds(backupData.chunks.forProto())
        blobsMap.putAll(backupData.chunkMap)
    }

    fun finalizeSnapshot(): Snapshot {
        val userName = getUserName()
        val deviceName = if (userName == null) {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL} - $userName"
        }

        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, ANDROID_ID)
        val snapshot = snapshotBuilder
            .setName(deviceName)
            .setAndroidId(androidId)
            .setSdkInt(Build.VERSION.SDK_INT)
            .setAndroidIncremental(Build.VERSION.INCREMENTAL)
            .setD2D(settingsManager.d2dBackupsEnabled())
            .putAllApps(appBuilderMap.mapValues { it.value.build() })
            .putAllBlobs(blobsMap)
            .build()
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
