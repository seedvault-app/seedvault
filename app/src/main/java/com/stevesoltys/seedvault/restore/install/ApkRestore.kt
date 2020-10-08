package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.transport.backup.copyStreamsAndGetHash
import com.stevesoltys.seedvault.transport.backup.getSignatures
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException

private val TAG = ApkRestore::class.java.simpleName

internal class ApkRestore(
    private val context: Context,
    private val restorePlugin: RestorePlugin,
    private val apkInstaller: ApkInstaller
) {

    private val pm = context.packageManager

    fun restore(token: Long, packageMetadataMap: PackageMetadataMap) = flow {
        // filter out packages without APK and get total
        val packages = packageMetadataMap.filter { it.value.hasApk() }
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
                restore(this, token, packageName, metadata, installResult)
            } catch (e: IOException) {
                Log.e(TAG, "Error re-installing APK for $packageName.", e)
                emit(fail(installResult, packageName))
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error re-installing APK for $packageName.", e)
                emit(fail(installResult, packageName))
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout while re-installing APK for $packageName.", e)
                emit(fail(installResult, packageName))
            }
        }
        installResult.isFinished = true
        emit(installResult)
    }

    @Suppress("ThrowsCount", "BlockingMethodInNonBlockingContext") // flows on Dispatcher.IO
    @Throws(IOException::class, SecurityException::class)
    private suspend fun restore(
        collector: FlowCollector<InstallResult>,
        token: Long,
        packageName: String,
        metadata: PackageMetadata,
        installResult: MutableInstallResult
    ) {
        // create a cache file to write the APK into
        val cachedApk = File.createTempFile(packageName, ".apk", context.cacheDir)
        // copy APK to cache file and calculate SHA-256 hash while we are at it
        val inputStream = restorePlugin.getApkInputStream(token, packageName)
        val sha256 = copyStreamsAndGetHash(inputStream, cachedApk.outputStream())

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

        // ensure system apps are actually installed and newer system apps as well
        if (metadata.system) {
            try {
                val installedPackageInfo = pm.getPackageInfo(packageName, 0)
                // metadata.version is not null, because here hasApk() must be true
                val isOlder = metadata.version!! <= installedPackageInfo.longVersionCode
                if (isOlder || !installedPackageInfo.isSystemApp()) throw NameNotFoundException()
            } catch (e: NameNotFoundException) {
                Log.w(TAG, "Not installing $packageName because older or not a system app here.")
                // TODO consider reporting different status here to prevent manual installs
                collector.emit(fail(installResult, packageName))
                return
            }
        }

        // install APK and emit updates from it
        val result = apkInstaller.install(cachedApk, packageName, metadata.installer, installResult)
        collector.emit(result)
    }

    private fun fail(installResult: MutableInstallResult, packageName: String): InstallResult {
        return installResult.update(packageName) { it.copy(state = FAILED) }
    }

}
