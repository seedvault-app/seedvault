package com.stevesoltys.seedvault.transport.backup

import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.util.Log
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.settings.SettingsManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

private val TAG = ApkBackup::class.java.simpleName

class ApkBackup(
        private val pm: PackageManager,
        private val clock: Clock,
        private val settingsManager: SettingsManager,
        private val metadataManager: MetadataManager) {

    @Throws(IOException::class)
    fun backupApkIfNecessary(packageInfo: PackageInfo, streamGetter: () -> OutputStream): Boolean {
        // do not back up @pm@
        val packageName = packageInfo.packageName
        if (packageName == MAGIC_PACKAGE_MANAGER) return false

        // do not back up when setting is not enabled
        if (!settingsManager.backupApks()) return false

        // do not back up system apps that haven't been updated
        val isSystemApp = packageInfo.applicationInfo.flags and FLAG_SYSTEM != 0
        val isUpdatedSystemApp = packageInfo.applicationInfo.flags and FLAG_UPDATED_SYSTEM_APP != 0
        if (isSystemApp && !isUpdatedSystemApp) {
            Log.d(TAG, "Package $packageName is vanilla system app. Not backing it up.")
            return false
        }

        // get cached metadata about package
        val packageMetadata = metadataManager.getPackageMetadata(packageName)
                ?: PackageMetadata(time = clock.time())

        // TODO remove when adding support in [signaturesChanged]
        if (packageInfo.signingInfo.hasMultipleSigners()) {
            Log.e(TAG, "Package $packageName has multiple signers. Not backing it up.")
            return false
        }

        // get signatures
        val signatures = getSignatures(packageInfo.signingInfo)
        if (signatures.isEmpty()) {
            Log.e(TAG, "Package $packageName has no signatures. Not backing it up.")
            return false
        }

        // get version codes
        val version = packageInfo.longVersionCode
        val backedUpVersion = packageMetadata.version ?: 0L  // no version will cause backup

        // do not backup if we have the version already and signatures did not change
        if (version <= backedUpVersion && !signaturesChanged(packageMetadata, signatures)) {
            Log.d(TAG, "Package $packageName with version $version already has a backup ($backedUpVersion) with the same signature. Not backing it up.")
            return false
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

        // copy the APK to the storage's output
        streamGetter.invoke().use { outputStream ->
            inputStream.use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        Log.d(TAG, "Backed up new APK of $packageName with version $version.")

        // update the metadata
        val installer = pm.getInstallerPackageName(packageName)
        val updatedMetadata = PackageMetadata(
                time = clock.time(),
                version = version,
                installer = installer,
                signatures = signatures
        )
        metadataManager.onApkBackedUp(packageName, updatedMetadata)
        return true
    }

    private fun getSignatures(signingInfo: SigningInfo): List<String> {
        val signatures = ArrayList<String>()
        if (signingInfo.hasMultipleSigners()) {
            for (sig in signingInfo.apkContentsSigners) {
                signatures.add(hashSignature(sig).encodeBase64())
            }
        } else {
            for (sig in signingInfo.signingCertificateHistory) {
                signatures.add(hashSignature(sig).encodeBase64())
            }
        }
        return signatures
    }

    private fun hashSignature(signature: Signature): ByteArray {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(signature.toByteArray())
            return digest.digest()
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "No SHA-256 algorithm found!", e)
            throw AssertionError(e)
        }
    }

    private fun signaturesChanged(packageMetadata: PackageMetadata, signatures: List<String>): Boolean {
        // no signatures in package metadata counts as them not having changed
        if (packageMetadata.signatures == null) return false
        // TODO this is probably more complicated, need to verify
        //      1. multiple signers: need to match all signatures in list
        //      2. single signer (with or without history): the intersection of both lists must not be empty.
        return packageMetadata.signatures.intersect(signatures).isEmpty()
    }

}
