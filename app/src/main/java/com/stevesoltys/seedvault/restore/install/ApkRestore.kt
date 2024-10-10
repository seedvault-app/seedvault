/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.app.backup.IBackupManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.SigningInfo
import android.util.Log
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.getBlobHandles
import com.stevesoltys.seedvault.restore.RestoreService
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.worker.hashSignature
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale

private val TAG = ApkRestore::class.java.simpleName

internal class ApkRestore(
    private val context: Context,
    private val backupManager: IBackupManager,
    private val backupStateManager: BackupStateManager,
    private val backendManager: BackendManager,
    private val loader: Loader,
    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin,
    private val crypto: Crypto,
    private val splitCompatChecker: ApkSplitCompatibilityChecker,
    private val apkInstaller: ApkInstaller,
    private val installRestriction: InstallRestriction,
) {

    private val pm = context.packageManager
    private val backend get() = backendManager.backend

    private val mInstallResult = MutableStateFlow(InstallResult())
    val installResult = mInstallResult.asStateFlow()

    suspend fun restore(backup: RestorableBackup) {
        val isAllowedToInstallApks = installRestriction.isAllowedToInstallApks()
        // assemble all apps in a list and sort it by name, than transform it back to a (sorted) map
        val packages = backup.packageMetadataMap.mapNotNull { (packageName, metadata) ->
            // We need to exclude the DocumentsProvider used to retrieve backup data.
            // Otherwise, it gets killed when we install it, terminating our restoration.
            if (packageName == backend.providerPackageName) return@mapNotNull null
            // The @pm@ package needs to be included in [backup], but can't be installed like an app
            if (packageName == MAGIC_PACKAGE_MANAGER) return@mapNotNull null
            // we don't filter out apps without APK, so the user can manually install them
            // exception is system apps without APK, as those can usually not be installed manually
            if (metadata.system && !metadata.hasApk()) return@mapNotNull null
            // apps that made it here get a state class for tracking
            ApkInstallResult(
                packageName = packageName,
                state = if (isAllowedToInstallApks) QUEUED else FAILED,
                metadata = metadata,
            )
        }.sortedBy { apkInstallResult -> // sort list alphabetically ignoring case
            apkInstallResult.name?.lowercase(Locale.getDefault())
        }.associateBy { apkInstallResult -> // use a map, so we can quickly update individual apps
            apkInstallResult.packageName
        }
        if (!isAllowedToInstallApks) { // not allowed to install, so return list with all failed
            mInstallResult.value = InstallResult(packages, true)
            return
        }
        mInstallResult.value = InstallResult(packages)
        val i = Intent(context, RestoreService::class.java)
        val autoRestore = backupStateManager.isAutoRestoreEnabled
        try {
            // don't use startForeground(), because we may stop it sooner than the system likes
            context.startService(i)
            // disable auto-restore before installing apps, if it was enabled before
            if (autoRestore) backupManager.setAutoRestore(false)
            reInstallApps(backup, packages.asIterable().reversed())
        } finally {
            // re-enable auto-restore, if it was enabled before
            if (autoRestore) backupManager.setAutoRestore(true)
            context.stopService(i)
        }
        mInstallResult.update { it.copy(isFinished = true) }
    }

    private suspend fun reInstallApps(
        backup: RestorableBackup,
        packages: List<Map.Entry<String, ApkInstallResult>>,
    ) {
        // re-install individual packages and emit updates (start from last and work your way up)
        for ((packageName, apkInstallResult) in packages) {
            try {
                if (isInstalled(packageName, apkInstallResult.metadata)) {
                    mInstallResult.update { result ->
                        result.update(packageName) { it.copy(state = SUCCEEDED) }
                    }
                } else if (!apkInstallResult.metadata.hasApk()) { // no APK available for install
                    mInstallResult.update { it.fail(packageName) }
                } else {
                    restore(backup, packageName, apkInstallResult.metadata)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error re-installing APK for $packageName.", e)
                mInstallResult.update { it.fail(packageName) }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error re-installing APK for $packageName.", e)
                mInstallResult.update { it.fail(packageName) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout while re-installing APK for $packageName.", e)
                mInstallResult.update { it.fail(packageName) }
            } catch (e: Exception) {
                if (e::class.simpleName == "MockKException") throw e
                Log.e(TAG, "Unexpected exception while re-installing APK for $packageName.", e)
                mInstallResult.update { it.fail(packageName) }
            }
        }
    }

    @Throws(SecurityException::class)
    private fun isInstalled(packageName: String, metadata: PackageMetadata): Boolean {
        @Suppress("DEPRECATION") // GET_SIGNATURES is needed even though deprecated
        val flags = GET_SIGNING_CERTIFICATES or GET_SIGNATURES
        val packageInfo = try {
            pm.getPackageInfo(packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return false
        val signatures = metadata.signatures
        if (signatures != null && signatures != packageInfo.signingInfo.getSignatures()) {
            // this will get caught and flag app as failed, could receive dedicated handling later
            throw SecurityException("Signature mismatch for $packageName")
        }
        return packageInfo.longVersionCode >= (metadata.version ?: 0)
    }

    @Suppress("ThrowsCount")
    @Throws(
        GeneralSecurityException::class,
        UnsupportedVersionException::class,
        IOException::class,
        SecurityException::class,
    )
    private suspend fun restore(
        backup: RestorableBackup,
        packageName: String,
        metadata: PackageMetadata,
    ) {
        // show that app is in progress, before we start downloading stuff
        mInstallResult.update {
            it.update(packageName) { result ->
                result.copy(state = IN_PROGRESS)
            }
        }

        // cache the APK and get its hash
        val (cachedApk, sha256) = cacheApk(backup, packageName, metadata.baseApkChunkIds)

        // check APK's SHA-256 hash for backup versions before 2
        if (backup.version < 2 && metadata.sha256 != sha256) throw SecurityException(
            "Package $packageName has sha256 '$sha256', but '${metadata.sha256}' expected."
        )

        // parse APK (GET_SIGNATURES is needed even though deprecated)
        @Suppress("DEPRECATION") val flags = GET_SIGNING_CERTIFICATES or GET_SIGNATURES
        val packageInfo = pm.getPackageArchiveInfo(cachedApk.absolutePath, flags)
            ?: throw IOException("getPackageArchiveInfo returned null")

        // check APK package name
        if (packageName != packageInfo.packageName) throw SecurityException(
            "Package $packageName expected, but ${packageInfo.packageName} found."
        )

        // check APK version code
        if (metadata.version != packageInfo.longVersionCode) {
            Log.w(
                TAG, "Package $packageName expects version code ${metadata.version}," +
                    "but has ${packageInfo.longVersionCode}."
            )
            // TODO should we let this one pass,
            //  maybe once we can revert PackageMetadata during backup?
        }

        // check signatures
        if (metadata.signatures != packageInfo.signingInfo.getSignatures()) {
            Log.w(TAG, "Package $packageName expects different signatures.")
            // TODO should we let this one pass, the sha256 hash already verifies the APK?
        }

        // get app icon and label (name)
        val appInfo = packageInfo.applicationInfo?.apply {
            // set APK paths before, so package manager can find it for icon extraction
            sourceDir = cachedApk.absolutePath
            publicSourceDir = cachedApk.absolutePath
        }
        val icon = appInfo?.loadIcon(pm)
        val name = appInfo?.let { pm.getApplicationLabel(it).toString() }

        mInstallResult.update {
            it.update(packageName) { result ->
                result.copy(state = IN_PROGRESS, name = name, icon = icon)
            }
        }

        // ensure system apps are actually already installed and newer system apps as well
        if (metadata.system) shouldInstallSystemApp(packageName, metadata)?.let {
            mInstallResult.value = it
            return
        }

        // process further APK splits, if available
        val cachedApks = cacheSplitsIfNeeded(backup, packageName, cachedApk, metadata.splits)
        if (cachedApks == null) {
            Log.w(TAG, "Not installing $packageName because of incompatible splits.")
            mInstallResult.update { it.fail(packageName) }
            return
        }

        // install APK and emit updates from it
        val result =
            apkInstaller.install(cachedApks, packageName, metadata.installer, installResult.value)
        mInstallResult.value = result
    }

    /**
     * Retrieves APK splits from [Backend] and caches them locally.
     *
     * @throws SecurityException if a split has an unexpected SHA-256 hash.
     * @return a list of all APKs that need to be installed
     * or null if the splits are incompatible with this restore device.
     */
    @Throws(IOException::class, SecurityException::class)
    private suspend fun cacheSplitsIfNeeded(
        backup: RestorableBackup,
        packageName: String,
        cachedApk: File,
        splits: List<ApkSplit>?,
    ): List<File>? {
        // if null, there are no splits, so we just have a single base APK to consider
        val splitNames = splits?.map { it.name } ?: return listOf(cachedApk)

        // return null when splits are incompatible
        if (!splitCompatChecker.isCompatible(backup.deviceName, splitNames)) return null

        // store coming splits in a list
        val cachedApks = ArrayList<File>(splits.size + 1).apply {
            add(cachedApk) // don't forget the base APK
        }
        splits.forEach { apkSplit -> // cache and check all splits
            val suffix = if (backup.version == 0.toByte()) "_${apkSplit.sha256}" else apkSplit.name
            val (file, sha256) = cacheApk(backup, packageName, apkSplit.chunkIds, suffix)
            // check APK split's SHA-256 hash for backup versions before 2
            if (backup.version < 2 && apkSplit.sha256 != sha256) throw SecurityException(
                "$packageName:${apkSplit.name} has sha256 '$sha256'," +
                    " but '${apkSplit.sha256}' expected."
            )
            cachedApks.add(file)
        }
        return cachedApks
    }

    /**
     * Retrieves an APK from the [Backend] and caches it locally
     * while calculating its SHA-256 hash.
     *
     * @return a [Pair] of the cached [File] and SHA-256 hash.
     */
    @Throws(GeneralSecurityException::class, UnsupportedVersionException::class, IOException::class)
    private suspend fun cacheApk(
        backup: RestorableBackup,
        packageName: String,
        chunkIds: List<String>?,
        suffix: String = "",
    ): Pair<File, String> {
        // create a cache file to write the APK into
        val cachedApk = File.createTempFile(packageName + suffix, ".apk", context.cacheDir)
        // copy APK to cache file and calculate SHA-256 hash while we are at it
        val inputStream = when (backup.version) {
            0.toByte() -> {
                legacyStoragePlugin.getApkInputStream(backup.token, packageName, suffix)
            }
            1.toByte() -> {
                val name = crypto.getNameForApk(backup.salt, packageName, suffix)
                backend.load(LegacyAppBackupFile.Blob(backup.token, name))
            }
            else -> {
                val repoId = backup.repoId ?: error("No repoId for v2 backup")
                val snapshot = backup.snapshot ?: error("No snapshot for v2 backup")
                val handles = chunkIds?.let {
                    snapshot.getBlobHandles(repoId, it)
                } ?: error("No chunkIds for $packageName-$suffix")
                loader.loadFiles(handles)
            }
        }
        val sha256 = copyStreamsAndGetHash(inputStream, cachedApk.outputStream())
        return Pair(cachedApk, sha256)
    }

    /**
     * Returns null if this system app should get re-installed,
     * or a new [InstallResult] to be emitted otherwise.
     */
    private fun shouldInstallSystemApp(
        packageName: String,
        metadata: PackageMetadata,
    ): InstallResult? {
        val installedPackageInfo = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Not installing system app $packageName because not installed here.")
            // we report a different FAILED status here to prevent manual installs
            return installResult.value.fail(packageName, FAILED_SYSTEM_APP)
        }
        // metadata.version is not null, because here hasApk() must be true
        val isOlder = metadata.version!! <= installedPackageInfo.longVersionCode
        return if (isOlder) {
            Log.w(TAG, "Not installing $packageName because ours is older.")
            installResult.value.update(packageName) { it.copy(state = SUCCEEDED) }
        } else if (!installedPackageInfo.isSystemApp()) {
            Log.w(TAG, "Not installing $packageName because not a system app here.")
            installResult.value.update(packageName) { it.copy(state = SUCCEEDED) }
        } else {
            null // everything is good, we can re-install this
        }
    }

    /**
     * Once [InstallResult.isFinished] is true,
     * this can be called to re-check a package in state [FAILED].
     * If it is now installed, the state will be changed to [SUCCEEDED].
     */
    fun reCheckFailedPackage(packageName: String) {
        check(installResult.value.isFinished) {
            "re-checking failed packages only allowed when finished"
        }
        if (context.packageManager.isInstalled(packageName)) mInstallResult.update { result ->
            result.update(packageName) { it.copy(state = SUCCEEDED) }
        }
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
