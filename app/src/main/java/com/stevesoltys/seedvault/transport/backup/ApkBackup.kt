package com.stevesoltys.seedvault.transport.backup

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.util.Log
import android.util.PackageUtils.computeSha256DigestBytes
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.settings.SettingsManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

private val TAG = ApkBackup::class.java.simpleName

class ApkBackup(
    private val pm: PackageManager,
    private val settingsManager: SettingsManager,
    private val metadataManager: MetadataManager
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
    suspend fun backupApkIfNecessary(
        packageInfo: PackageInfo,
        packageState: PackageState,
        streamGetter: suspend () -> OutputStream
    ): PackageMetadata? {
        // do not back up @pm@
        val packageName = packageInfo.packageName
        if (packageName == MAGIC_PACKAGE_MANAGER) return null

        // do not back up when setting is not enabled
        if (!settingsManager.backupApks()) return null

        // do not back up system apps that haven't been updated
        if (packageInfo.isNotUpdatedSystemApp()) {
            Log.d(TAG, "Package $packageName is vanilla system app. Not backing it up.")
            return null
        }

        // TODO remove when adding support for packages with multiple signers
        if (packageInfo.signingInfo.hasMultipleSigners()) {
            Log.e(TAG, "Package $packageName has multiple signers. Not backing it up.")
            return null
        }

        // get signatures
        val signatures = packageInfo.signingInfo.getSignatures()
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
                TAG,
                "Package $packageName with version $version already has a backup ($backedUpVersion)" +
                        " with the same signature. Not backing it up."
            )
            return null
        }

        // get an InputStream for the APK
        val apk = File(packageInfo.applicationInfo.sourceDir)
        val inputStream = try {
            apk.inputStream()
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Error opening ${apk.absolutePath} for backup.", e)
            throw IOException(e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Error opening ${apk.absolutePath} for backup.", e)
            throw IOException(e)
        }

        // copy the APK to the storage's output and calculate SHA-256 hash while at it
        val messageDigest = MessageDigest.getInstance("SHA-256")
        streamGetter().use { outputStream ->
            inputStream.use { inputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream.read(buffer)
                while (bytes >= 0) {
                    outputStream.write(buffer, 0, bytes)
                    messageDigest.update(buffer, 0, bytes)
                    bytes = inputStream.read(buffer)
                }
            }
        }
        val sha256 = messageDigest.digest().encodeBase64()
        Log.d(TAG, "Backed up new APK of $packageName with version $version.")

        // return updated metadata
        return PackageMetadata(
            state = packageState,
            version = version,
            installer = pm.getInstallerPackageName(packageName),
            sha256 = sha256,
            signatures = signatures
        )
    }

    private fun signaturesChanged(
        packageMetadata: PackageMetadata,
        signatures: List<String>
    ): Boolean {
        // no signatures in package metadata counts as them not having changed
        if (packageMetadata.signatures == null) return false
        // TODO to support multiple signers check if lists differ
        return packageMetadata.signatures.intersect(signatures).isEmpty()
    }

}

/**
 * Returns a list of Base64 encoded SHA-256 signature hashes.
 */
fun SigningInfo.getSignatures(): List<String> {
    return if (hasMultipleSigners()) {
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
