package com.stevesoltys.seedvault.transport.restore

import android.content.Context
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import android.util.Log
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.transport.backup.getSignatures
import com.stevesoltys.seedvault.transport.backup.isSystemApp
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.FAILED
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.IN_PROGRESS
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.QUEUED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap


private val TAG = ApkRestore::class.java.simpleName

internal class ApkRestore(
        private val context: Context,
        private val restorePlugin: RestorePlugin,
        private val apkInstaller: ApkInstaller = ApkInstaller(context)) {

    private val pm = context.packageManager

    @ExperimentalCoroutinesApi
    fun restore(token: Long, packageMetadataMap: PackageMetadataMap) = flow {
        // filter out packages without APK and get total
        val packages = packageMetadataMap.filter { it.value.hasApk() }
        val total = packages.size
        var progress = 0

        // queue all packages and emit LiveData
        val installResult = MutableInstallResult(total)
        packages.forEach { (packageName, _) ->
            progress++
            installResult[packageName] = ApkRestoreResult(packageName, progress, total, QUEUED)
        }
        emit(installResult)

        // restore individual packages and emit updates
        for ((packageName, metadata) in packages) {
            try {
                @Suppress("BlockingMethodInNonBlockingContext")  // flows on Dispatcher.IO
                restore(token, packageName, metadata, installResult).collect {
                    emit(it)
                }
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
    }

    @ExperimentalCoroutinesApi
    @Suppress("BlockingMethodInNonBlockingContext")  // flows on Dispatcher.IO
    @Throws(IOException::class, SecurityException::class)
    private fun restore(token: Long, packageName: String, metadata: PackageMetadata, installResult: MutableInstallResult) = flow {
        // create a cache file to write the APK into
        val cachedApk = File.createTempFile(packageName, ".apk", context.cacheDir)
        // copy APK to cache file and calculate SHA-256 hash while we are at it
        val messageDigest = MessageDigest.getInstance("SHA-256")
        restorePlugin.getApkInputStream(token, packageName).use { inputStream ->
            cachedApk.outputStream().use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream.read(buffer)
                while (bytes >= 0) {
                    outputStream.write(buffer, 0, bytes)
                    messageDigest.update(buffer, 0, bytes)
                    bytes = inputStream.read(buffer)
                }
            }
        }

        // check APK's SHA-256 hash
        val sha256 = messageDigest.digest().encodeBase64()
        if (metadata.sha256 != sha256) {
            throw SecurityException("Package $packageName has sha256 '$sha256', but '${metadata.sha256}' expected.")
        }

        // parse APK (GET_SIGNATURES is needed even though deprecated)
        @Suppress("DEPRECATION") val flags = GET_SIGNING_CERTIFICATES or GET_SIGNATURES
        val packageInfo = pm.getPackageArchiveInfo(cachedApk.absolutePath, flags)
                ?: throw IOException("getPackageArchiveInfo returned null")

        // check APK package name
        if (packageName != packageInfo.packageName) {
            throw SecurityException("Package $packageName expected, but ${packageInfo.packageName} found.")
        }

        // check APK version code
        if (metadata.version != packageInfo.longVersionCode) {
            Log.w(TAG, "Package $packageName expects version code ${metadata.version}, but has ${packageInfo.longVersionCode}.")
            // TODO should we let this one pass, maybe once we can revert PackageMetadata during backup?
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
        val name = pm.getApplicationLabel(appInfo) ?: packageName

        installResult.update(packageName) { it.copy(status = IN_PROGRESS, name = name, icon = icon) }
        emit(installResult)

        // ensure system apps are actually installed and newer system apps as well
        if (metadata.system) {
            try {
                val installedPackageInfo = pm.getPackageInfo(packageName, 0)
                // metadata.version is not null, because here hasApk() must be true
                val isOlder = metadata.version!! <= installedPackageInfo.longVersionCode
                if (isOlder || !installedPackageInfo.isSystemApp()) throw NameNotFoundException()
            } catch (e: NameNotFoundException) {
                Log.w(TAG, "Not installing $packageName because older or not a system app here.")
                emit(fail(installResult, packageName))
                return@flow
            }
        }

        // install APK and emit updates from it
        apkInstaller.install(cachedApk, packageName, metadata.installer, installResult).collect { result ->
            emit(result)
        }
    }

    private fun fail(installResult: MutableInstallResult, packageName: String): InstallResult {
        return installResult.update(packageName) { it.copy(status = FAILED) }
    }

}

internal typealias InstallResult = Map<String, ApkRestoreResult>

internal fun InstallResult.getInProgress(): ApkRestoreResult? {
    val filtered = filterValues { result -> result.status == IN_PROGRESS }
    if (filtered.isEmpty()) return null
    check(filtered.size == 1) { "More than one package in progress: ${filtered.keys}" }
    return filtered.values.first()
}

internal class MutableInstallResult(initialCapacity: Int) : ConcurrentHashMap<String, ApkRestoreResult>(initialCapacity) {
    fun update(packageName: String, updateFun: (ApkRestoreResult) -> ApkRestoreResult): MutableInstallResult {
        val result = get(packageName)
        check(result != null) { "ApkRestoreResult for $packageName does not exist." }
        set(packageName, updateFun(result))
        return this
    }
}

internal data class ApkRestoreResult(
        val packageName: CharSequence,
        val progress: Int,
        val total: Int,
        val status: ApkRestoreStatus,
        val name: CharSequence? = null,
        val icon: Drawable? = null
) : Comparable<ApkRestoreResult> {
    override fun compareTo(other: ApkRestoreResult): Int {
        return other.progress.compareTo(progress)
    }
}

internal enum class ApkRestoreStatus {
    QUEUED, IN_PROGRESS, SUCCEEDED, FAILED
}
