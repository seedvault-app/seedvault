/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.util.Log
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.restore.RestorableBackup
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import com.stevesoltys.seedvault.worker.copyStreamsAndGetHash
import com.stevesoltys.seedvault.worker.getSignatures
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale

private val TAG = ApkRestore::class.java.simpleName

internal class ApkRestore(
    private val context: Context,
    private val pluginManager: StoragePluginManager,
    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin,
    private val crypto: Crypto,
    private val splitCompatChecker: ApkSplitCompatibilityChecker,
    private val apkInstaller: ApkInstaller,
    private val installRestriction: InstallRestriction,
) {

    private val pm = context.packageManager
    private val storagePlugin get() = pluginManager.appPlugin

    private val mInstallResult = MutableStateFlow(InstallResult())
    val installResult = mInstallResult.asStateFlow()

    suspend fun restore(backup: RestorableBackup) {
        val isAllowedToInstallApks = installRestriction.isAllowedToInstallApks()
        // assemble all apps in a list and sort it by name, than transform it back to a (sorted) map
        val packages = backup.packageMetadataMap.mapNotNull { (packageName, metadata) ->
            // We need to exclude the DocumentsProvider used to retrieve backup data.
            // Otherwise, it gets killed when we install it, terminating our restoration.
            if (packageName == storagePlugin.providerPackageName) return@mapNotNull null
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

        // re-install individual packages and emit updates (start from last and work your way up)
        for ((packageName, apkInstallResult) in packages.asIterable().reversed()) {
            try {
                if (apkInstallResult.metadata.hasApk()) {
                    restore(backup, packageName, apkInstallResult.metadata)
                } else {
                    mInstallResult.update { it.fail(packageName) }
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
                Log.e(TAG, "Unexpected exception while re-installing APK for $packageName.", e)
                mInstallResult.update { it.fail(packageName) }
            }
        }
        mInstallResult.update { it.copy(isFinished = true) }
    }

    @Suppress("ThrowsCount")
    @Throws(IOException::class, SecurityException::class)
    private suspend fun restore(
        backup: RestorableBackup,
        packageName: String,
        metadata: PackageMetadata,
    ) {
        // cache the APK and get its hash
        val (cachedApk, sha256) = cacheApk(backup.version, backup.token, backup.salt, packageName)

        // check APK's SHA-256 hash
        if (metadata.sha256 != sha256) throw SecurityException(
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
     * Retrieves APK splits from [StoragePlugin] and caches them locally.
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
            val salt = backup.salt
            val (file, sha256) = cacheApk(backup.version, backup.token, salt, packageName, suffix)
            // check APK split's SHA-256 hash
            if (apkSplit.sha256 != sha256) throw SecurityException(
                "$packageName:${apkSplit.name} has sha256 '$sha256'," +
                    " but '${apkSplit.sha256}' expected."
            )
            cachedApks.add(file)
        }
        return cachedApks
    }

    /**
     * Retrieves an APK from the [StoragePlugin] and caches it locally
     * while calculating its SHA-256 hash.
     *
     * @return a [Pair] of the cached [File] and SHA-256 hash.
     */
    @Throws(IOException::class)
    private suspend fun cacheApk(
        version: Byte,
        token: Long,
        salt: String,
        packageName: String,
        suffix: String = "",
    ): Pair<File, String> {
        // create a cache file to write the APK into
        val cachedApk = File.createTempFile(packageName + suffix, ".apk", context.cacheDir)
        // copy APK to cache file and calculate SHA-256 hash while we are at it
        val inputStream = if (version == 0.toByte()) {
            legacyStoragePlugin.getApkInputStream(token, packageName, suffix)
        } else {
            val name = crypto.getNameForApk(salt, packageName, suffix)
            storagePlugin.getInputStream(token, name)
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
