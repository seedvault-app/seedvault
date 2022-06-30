package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.util.Log
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.restore.RestorableBackup
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.backup.copyStreamsAndGetHash
import com.stevesoltys.seedvault.transport.backup.getSignatures
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException

private val TAG = ApkRestore::class.java.simpleName

internal class ApkRestore(
    private val context: Context,
    private val storagePlugin: StoragePlugin,
    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin,
    private val crypto: Crypto,
    private val splitCompatChecker: ApkSplitCompatibilityChecker,
    private val apkInstaller: ApkInstaller,
) {

    private val pm = context.packageManager

    @Suppress("BlockingMethodInNonBlockingContext")
    fun restore(backup: RestorableBackup) = flow {
        // filter out packages without APK and get total
        val packages = backup.packageMetadataMap.filter {
            // We also need to exclude the DocumentsProvider used to retrieve backup data.
            // Otherwise, it gets killed when we install it, terminating our restoration.
            val isStorageProvider = it.key == storagePlugin.providerPackageName
            it.value.hasApk() && !isStorageProvider
        }
        val total = packages.size
        var progress = 0

        // queue all packages and emit LiveData
        val installResult = MutableInstallResult(total)
        packages.forEach { (packageName, metadata) ->
            progress++
            installResult[packageName] = ApkInstallResult(
                packageName = packageName,
                progress = progress,
                state = QUEUED,
                installerPackageName = metadata.installer
            )
        }
        emit(installResult)

        // re-install individual packages and emit updates
        for ((packageName, metadata) in packages) {
            try {
                restore(this, backup, packageName, metadata, installResult)
            } catch (e: IOException) {
                Log.e(TAG, "Error re-installing APK for $packageName.", e)
                emit(installResult.fail(packageName))
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error re-installing APK for $packageName.", e)
                emit(installResult.fail(packageName))
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout while re-installing APK for $packageName.", e)
                emit(installResult.fail(packageName))
            }
        }
        installResult.isFinished = true
        emit(installResult)
    }

    @Suppress("ThrowsCount", "BlockingMethodInNonBlockingContext") // flows on Dispatcher.IO
    @Throws(IOException::class, SecurityException::class)
    private suspend fun restore(
        collector: FlowCollector<InstallResult>,
        backup: RestorableBackup,
        packageName: String,
        metadata: PackageMetadata,
        installResult: MutableInstallResult,
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
        val appInfo = packageInfo.applicationInfo.apply {
            // set APK paths before, so package manager can find it for icon extraction
            sourceDir = cachedApk.absolutePath
            publicSourceDir = cachedApk.absolutePath
        }
        val icon = appInfo.loadIcon(pm)
        val name = pm.getApplicationLabel(appInfo)

        installResult.update(packageName) { result ->
            result.copy(state = IN_PROGRESS, name = name, icon = icon)
        }
        collector.emit(installResult)

        // ensure system apps are actually already installed and newer system apps as well
        if (metadata.system) {
            shouldInstallSystemApp(packageName, metadata, installResult)?.let {
                collector.emit(it)
                return
            }
        }

        // process further APK splits, if available
        val cachedApks =
            cacheSplitsIfNeeded(backup, packageName, cachedApk, metadata.splits)
        if (cachedApks == null) {
            Log.w(TAG, "Not installing $packageName because of incompatible splits.")
            collector.emit(installResult.fail(packageName))
            return
        }

        // install APK and emit updates from it
        val result =
            apkInstaller.install(cachedApks, packageName, metadata.installer, installResult)
        collector.emit(result)
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
    @Suppress("BlockingMethodInNonBlockingContext") // flows on Dispatcher.IO
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
            @Suppress("Deprecation")
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
        installResult: MutableInstallResult,
    ): InstallResult? {
        val installedPackageInfo = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Not installing system app $packageName because not installed here.")
            // we report a different FAILED status here to prevent manual installs
            return installResult.fail(packageName, FAILED_SYSTEM_APP)
        }
        // metadata.version is not null, because here hasApk() must be true
        val isOlder = metadata.version!! <= installedPackageInfo.longVersionCode
        return if (isOlder) {
            Log.w(TAG, "Not installing $packageName because ours is older.")
            installResult.update(packageName) { it.copy(state = SUCCEEDED) }
        } else if (!installedPackageInfo.isSystemApp()) {
            Log.w(TAG, "Not installing $packageName because not a system app here.")
            installResult.update(packageName) { it.copy(state = SUCCEEDED) }
        } else {
            null // everything is good, we can re-install this
        }
    }

}
