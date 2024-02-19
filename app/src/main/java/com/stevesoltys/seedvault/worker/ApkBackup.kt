/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.util.Log
import android.util.PackageUtils.computeSha256DigestBytes
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.isNotUpdatedSystemApp
import com.stevesoltys.seedvault.transport.backup.isTestOnly
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private val TAG = ApkBackup::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class ApkBackup(
    private val pm: PackageManager,
    private val crypto: Crypto,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager,
) {

    /**
     * Checks if a new APK needs to get backed up,
     * because the version code or the signatures have changed.
     * Only if an APK needs a backup, an [OutputStream] is obtained from the given streamGetter
     * and the APK binary written to it.
     *
     * @return new [PackageMetadata] if an APK backup was made or null if no backup was made.
     */
    @Throws(IOException::class)
    @SuppressLint("NewApi") // can be removed when minSdk is set to 30
    suspend fun backupApkIfNecessary(
        packageInfo: PackageInfo,
        streamGetter: suspend (name: String) -> OutputStream,
    ): PackageMetadata? {
        // do not back up @pm@
        val packageName = packageInfo.packageName
        if (packageName == MAGIC_PACKAGE_MANAGER) return null

        // do not back up when setting is not enabled
        if (!settingsManager.backupApks()) return null

        // do not back up if package is blacklisted
        if (!settingsManager.isBackupEnabled(packageName)) {
            Log.d(TAG, "Package $packageName is blacklisted. Not backing it up.")
            return null
        }

        // do not back up test-only apps as we can't re-install them anyway
        // see: https://commonsware.com/blog/2017/10/31/android-studio-3p0-flag-test-only.html
        if (packageInfo.isTestOnly()) {
            Log.d(TAG, "Package $packageName is test-only app. Not backing it up.")
            return null
        }

        // do not back up system apps that haven't been updated
        if (packageInfo.isNotUpdatedSystemApp()) {
            Log.d(TAG, "Package $packageName is vanilla system app. Not backing it up.")
            return null
        }

        // TODO remove when adding support for packages with multiple signers
        val signingInfo = packageInfo.signingInfo ?: return null
        if (signingInfo.hasMultipleSigners()) {
            Log.e(TAG, "Package $packageName has multiple signers. Not backing it up.")
            return null
        }

        // get signatures
        val signatures = signingInfo.getSignatures()
        if (signatures.isEmpty()) {
            Log.e(TAG, "Package $packageName has no signatures. Not backing it up.")
            return null
        }

        // get cached metadata about package
        val packageMetadata = metadataManager.getPackageMetadata(packageName)
            ?: PackageMetadata()

        // get version codes
        val version = packageInfo.longVersionCode
        val backedUpVersion = packageMetadata.version ?: 0L // no version will cause backup

        // do not backup if we have the version already and signatures did not change
        if (version <= backedUpVersion && !signaturesChanged(packageMetadata, signatures)) {
            Log.d(
                TAG, "Package $packageName with version $version" +
                    " already has a backup ($backedUpVersion)" +
                    " with the same signature. Not backing it up."
            )
            // We could also check if there are new feature module splits to back up,
            // but we rely on the app themselves to re-download those, if needed after restore.
            return null
        }

        // get an InputStream for the APK
        val sourceDir = packageInfo.applicationInfo?.sourceDir ?: return null
        val inputStream = getApkInputStream(sourceDir)
        // copy the APK to the storage's output and calculate SHA-256 hash while at it
        val name = crypto.getNameForApk(metadataManager.salt, packageName)
        val sha256 = copyStreamsAndGetHash(inputStream, streamGetter(name))

        // back up splits if they exist
        val splits =
            if (packageInfo.splitNames == null) null else backupSplitApks(packageInfo, streamGetter)

        Log.d(TAG, "Backed up new APK of $packageName with version ${packageInfo.versionName}.")

        // return updated metadata
        return packageMetadata.copy(
            version = version,
            installer = pm.getInstallSourceInfo(packageName).installingPackageName,
            splits = splits,
            sha256 = sha256,
            signatures = signatures
        )
    }

    private fun signaturesChanged(
        packageMetadata: PackageMetadata,
        signatures: List<String>,
    ): Boolean {
        // no signatures in package metadata counts as them not having changed
        if (packageMetadata.signatures == null) return false
        // TODO to support multiple signers check if lists differ
        return packageMetadata.signatures.intersect(signatures).isEmpty()
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
        streamGetter: suspend (name: String) -> OutputStream,
    ): List<ApkSplit> {
        check(packageInfo.splitNames != null)
        // attention: though not documented, splitSourceDirs can be null
        val splitSourceDirs = packageInfo.applicationInfo?.splitSourceDirs ?: emptyArray()
        check(packageInfo.splitNames.size == splitSourceDirs.size) {
            "Size Mismatch! ${packageInfo.splitNames.size} != ${splitSourceDirs.size} " +
                "splitNames is ${packageInfo.splitNames.toList()}, " +
                "but splitSourceDirs is ${splitSourceDirs.toList()}"
        }
        val splits = ArrayList<ApkSplit>(packageInfo.splitNames.size)
        for (i in packageInfo.splitNames.indices) {
            val split = backupSplitApk(
                packageName = packageInfo.packageName,
                splitName = packageInfo.splitNames[i],
                sourceDir = splitSourceDirs[i],
                streamGetter = streamGetter
            )
            splits.add(split)
        }
        return splits
    }

    @Throws(IOException::class)
    private suspend fun backupSplitApk(
        packageName: String,
        splitName: String,
        sourceDir: String,
        streamGetter: suspend (name: String) -> OutputStream,
    ): ApkSplit {
        // Calculate sha256 hash first to determine file name suffix.
        // We could also just use the split name as a suffix, but there is a theoretical risk
        // that we exceed the maximum file name length, so we use the hash instead.
        // The downside is that we need to read the file two times.
        val messageDigest = MessageDigest.getInstance("SHA-256")
        getApkInputStream(sourceDir).use { inputStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                messageDigest.update(buffer, 0, bytes)
                bytes = inputStream.read(buffer)
            }
        }
        val sha256 = messageDigest.digest().encodeBase64()
        val name = crypto.getNameForApk(metadataManager.salt, packageName, splitName)
        // copy the split APK to the storage stream
        getApkInputStream(sourceDir).use { inputStream ->
            streamGetter(name).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return ApkSplit(splitName, sha256)
    }

}

/**
 * Copy the APK from the given [InputStream] to the given [OutputStream]
 * and calculate the SHA-256 hash while at it.
 *
 * Both streams will be closed when the method returns.
 *
 * @return the APK's SHA-256 hash in Base64 format.
 */
@Throws(IOException::class)
fun copyStreamsAndGetHash(inputStream: InputStream, outputStream: OutputStream): String {
    val messageDigest = MessageDigest.getInstance("SHA-256")
    outputStream.use { oStream ->
        inputStream.use { inputStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                oStream.write(buffer, 0, bytes)
                messageDigest.update(buffer, 0, bytes)
                bytes = inputStream.read(buffer)
            }
        }
    }
    return messageDigest.digest().encodeBase64()
}

/**
 * Returns a list of Base64 encoded SHA-256 signature hashes.
 */
fun SigningInfo?.getSignatures(): List<String> {
    return if (this == null) {
        emptyList()
    } else if (hasMultipleSigners()) {
        apkContentsSigners.map { signature ->
            hashSignature(signature).encodeBase64()
        }
    } else {
        signingCertificateHistory.map { signature ->
            hashSignature(signature).encodeBase64()
        }
    }
}

private fun hashSignature(signature: Signature): ByteArray {
    return computeSha256DigestBytes(signature.toByteArray()) ?: throw AssertionError()
}
