/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.util.Log
import android.util.PackageUtils.computeSha256DigestBytes
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.SnapshotManager
import com.stevesoltys.seedvault.transport.backup.AppBackupManager
import com.stevesoltys.seedvault.transport.backup.BackupReceiver
import com.stevesoltys.seedvault.transport.backup.forProto
import com.stevesoltys.seedvault.transport.backup.hexFromProto
import com.stevesoltys.seedvault.transport.backup.isNotUpdatedSystemApp
import com.stevesoltys.seedvault.transport.backup.isTestOnly
import org.calyxos.seedvault.core.toHexString
import java.io.File
import java.io.FileInputStream
import java.io.IOException

private val TAG = ApkBackup::class.java.simpleName
internal const val BASE_SPLIT = "org.calyxos.seedvault.BASE_SPLIT"

internal class ApkBackup(
    private val pm: PackageManager,
    private val backupReceiver: BackupReceiver,
    private val appBackupManager: AppBackupManager,
    private val snapshotManager: SnapshotManager,
    private val settingsManager: SettingsManager,
) {

    private val snapshotCreator
        get() = appBackupManager.snapshotCreator ?: error("No SnapshotCreator")

    /**
     * Checks if a new APK needs to get backed up,
     * because the version code or the signatures have changed.
     * Only if APKs need backup, they get chunked and uploaded.
     *
     * @return new [PackageMetadata] if an APK backup was made or null if no backup was made.
     */
    @Throws(IOException::class)
    suspend fun backupApkIfNecessary(packageInfo: PackageInfo) {
        // do not back up @pm@
        val packageName = packageInfo.packageName
        if (packageName == MAGIC_PACKAGE_MANAGER) return

        // do not back up when setting is not enabled
        if (!settingsManager.backupApks()) return

        // do not back up if package is blacklisted
        if (!settingsManager.isBackupEnabled(packageName)) {
            Log.d(TAG, "Package $packageName is blacklisted. Not backing it up.")
            return
        }

        // do not back up test-only apps as we can't re-install them anyway
        // see: https://commonsware.com/blog/2017/10/31/android-studio-3p0-flag-test-only.html
        if (packageInfo.isTestOnly()) {
            Log.d(TAG, "Package $packageName is test-only app. Not backing it up.")
            return
        }

        // do not back up system apps that haven't been updated
        if (packageInfo.isNotUpdatedSystemApp()) {
            Log.d(TAG, "Package $packageName is vanilla system app. Not backing it up.")
            return
        }

        // TODO remove when adding support for packages with multiple signers
        val signingInfo = packageInfo.signingInfo ?: return
        if (signingInfo.hasMultipleSigners()) {
            Log.e(TAG, "Package $packageName has multiple signers. Not backing it up.")
            return
        }

        // get signatures
        val signatures = signingInfo.getSignaturesHex()
        if (signatures.isEmpty()) {
            Log.e(TAG, "Package $packageName has no signatures. Not backing it up.")
            return
        }

        // get info from latest snapshot
        val version = packageInfo.longVersionCode
        val oldApk = snapshotManager.latestSnapshot?.appsMap?.get(packageName)?.apk
        val backedUpVersion = oldApk?.versionCode ?: 0L // no version will cause backup

        // do not backup if we have the version already and signatures did not change
        if (version <= backedUpVersion && !signaturesChanged(oldApk, signatures)) {
            Log.d(
                TAG, "Package $packageName with version $version" +
                    " already has a backup ($backedUpVersion)" +
                    " with the same signature. Not backing it up."
            )
            // We could also check if there are new feature module splits to back up,
            // but we rely on the app themselves to re-download those, if needed after restore.
            return
        }

        // builder for Apk object
        val apkBuilder = Snapshot.Apk.newBuilder()
            .setVersionCode(version)
            .setInstaller(pm.getInstallSourceInfo(packageName).installingPackageName)
            .addAllSignatures(signatures.forProto())

        // get an InputStream for the APK
        val sourceDir = packageInfo.applicationInfo?.sourceDir ?: return
        // upload the APK to the backend
        getApkInputStream(sourceDir).use { inputStream ->
            backupReceiver.readFromStream(inputStream)
        }
        val backupData = backupReceiver.finalize()
        // store base split in builder
        val baseSplit = Snapshot.Split.newBuilder()
            .setName(BASE_SPLIT)
            .addAllChunkIds(backupData.chunks.forProto())
        apkBuilder
            .addSplits(baseSplit)
        val chunkMap = backupData.chunkMap.toMutableMap()

        // back up splits if they exist
        val splits = if (packageInfo.splitNames == null) {
            emptyList()
        } else {
            backupSplitApks(packageInfo, chunkMap)
        }
        apkBuilder.addAllSplits(splits)
        val apk = apkBuilder.build()
        snapshotCreator.onApkBackedUp(packageName, apk, chunkMap)

        Log.d(TAG, "Backed up new APK of $packageName with version ${packageInfo.versionName}.")
    }

    private fun signaturesChanged(
        apk: Snapshot.Apk?,
        signatures: List<String>,
    ): Boolean {
        // no signatures counts as them not having changed
        if (apk == null || apk.signaturesList.isNullOrEmpty()) return false
        val sigHex = apk.signaturesList.hexFromProto()
        // TODO to support multiple signers check if lists differ
        return sigHex.intersect(signatures.toSet()).isEmpty()
    }

    @Throws(IOException::class)
    private fun getApkInputStream(apkPath: String): FileInputStream {
        val apk = File(apkPath)
        return try {
            apk.inputStream()
        } catch (e: Exception) {
            // SAF may throw all sorts of exceptions, so wrap them in IOException
            Log.e(TAG, "Error opening ${apk.absolutePath} for backup.", e)
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    private suspend fun backupSplitApks(
        packageInfo: PackageInfo,
        chunkMap: MutableMap<String, Snapshot.Blob>,
    ): List<Snapshot.Split> {
        check(packageInfo.splitNames != null)
        // attention: though not documented, splitSourceDirs can be null
        val splitSourceDirs = packageInfo.applicationInfo?.splitSourceDirs ?: emptyArray()
        check(packageInfo.splitNames.size == splitSourceDirs.size) {
            "Size Mismatch! ${packageInfo.splitNames.size} != ${splitSourceDirs.size} " +
                "splitNames is ${packageInfo.splitNames.toList()}, " +
                "but splitSourceDirs is ${splitSourceDirs.toList()}"
        }
        val splits = ArrayList<Snapshot.Split>(packageInfo.splitNames.size)
        for (i in packageInfo.splitNames.indices) {
            // copy the split APK to the storage stream
            getApkInputStream(splitSourceDirs[i]).use { inputStream ->
                backupReceiver.readFromStream(inputStream)
            }
            val backupData = backupReceiver.finalize()
            val split = Snapshot.Split.newBuilder()
                .setName(packageInfo.splitNames[i])
                .addAllChunkIds(backupData.chunks.forProto())
                .build()
            splits.add(split)
            chunkMap.putAll(backupData.chunkMap)
        }
        return splits
    }

}

/**
 * Returns a list of lowercase hex encoded SHA-256 signature hashes.
 */
fun SigningInfo?.getSignaturesHex(): List<String> {
    return if (this == null) {
        emptyList()
    } else if (hasMultipleSigners()) {
        apkContentsSigners.map { signature ->
            hashSignature(signature).toHexString()
        }
    } else {
        signingCertificateHistory.map { signature ->
            hashSignature(signature).toHexString()
        }
    }
}

internal fun hashSignature(signature: Signature): ByteArray {
    return computeSha256DigestBytes(signature.toByteArray()) ?: throw AssertionError()
}
