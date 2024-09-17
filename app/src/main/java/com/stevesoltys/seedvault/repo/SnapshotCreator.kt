/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.Manifest
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
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Apk
import com.stevesoltys.seedvault.proto.Snapshot.App
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString

/**
 * Assembles snapshot information over the course of a single backup run
 * and creates a [Snapshot] object in the end by calling [finalizeSnapshot].
 */
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
        // as we can't ask [PackageInfo] for this, we keep a set of packages around
        packageService.launchableSystemApps.map { it.activityInfo.packageName }.toSet()
    }

    /**
     * Call this after all blobs for the given [apk] have been saved to the backend.
     * The [apk] must contain the ordered list of chunk IDs
     * and the given [blobMap] must have one [Blob] per chunk ID.
     */
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

    /**
     * Call this after all blobs for the package identified by the given [packageInfo]
     * have been saved to the backend.
     * The given [backupData] must contain the full ordered list of [BackupData.chunkIds]
     * and the [BackupData.blobMap] must have one [Blob] per chunk ID.
     *
     * Failure to call this method results in the package effectively not getting backed up.
     */
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

    /**
     * Call this after all blobs for the app icons have been saved to the backend.
     */
    fun onIconsBackedUp(backupData: BackupData) {
        snapshotBuilder.addAllIconChunkIds(backupData.chunkIds.forProto())
        blobsMap.putAll(backupData.blobMap)
    }

    /**
     * Must get called after all backup data was saved to the backend.
     * Returns the assembled [Snapshot] which must be saved to the backend as well
     * to complete the current backup run.
     *
     * Internal state will be cleared to free up memory.
     * Still, it isn't safe to re-use an instance of this class, after it has been finalized.
     */
    fun finalizeSnapshot(): Snapshot {
        log.info { "finalizeSnapshot()" }
        @SuppressLint("HardwareIds")
        val snapshot = snapshotBuilder.apply {
            version = VERSION.toInt()
            token = clock.time()
            name = "${Build.MANUFACTURER} ${Build.MODEL}"
            user = getUserName() ?: ""
            androidId = Settings.Secure.getString(context.contentResolver, ANDROID_ID) ?: ""
            sdkInt = Build.VERSION.SDK_INT
            androidIncremental = Build.VERSION.INCREMENTAL
            d2D = true
            putAllApps(appBuilderMap.mapValues { it.value.build() })
            putAllBlobs(this@SnapshotCreator.blobsMap)
        }.build()
        appBuilderMap.clear()
        snapshotBuilder.clear()
        blobsMap.clear()
        return snapshot
    }

    private fun getUserName(): String? {
        @Suppress("UNRESOLVED_REFERENCE") // hidden AOSP API
        val perm = Manifest.permission.QUERY_USERS
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
