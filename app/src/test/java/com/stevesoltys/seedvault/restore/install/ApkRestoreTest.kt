package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.restore.RestorableBackup
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
@ExperimentalCoroutinesApi
internal class ApkRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }
    private val storagePlugin: StoragePlugin = mockk()
    private val legacyStoragePlugin: LegacyStoragePlugin = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()

    private val apkRestore: ApkRestore = ApkRestore(
        strictContext,
        storagePlugin,
        legacyStoragePlugin,
        crypto,
        splitCompatChecker,
        apkInstaller
    )

    private val icon: Drawable = mockk()

    private val deviceName = metadata.deviceName
    private val packageName = packageInfo.packageName
    private val packageMetadata = PackageMetadata(
        time = Random.nextLong(),
        version = packageInfo.longVersionCode - 1,
        installer = getRandomString(),
        sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
        signatures = listOf("AwIB")
    )
    private val packageMetadataMap: PackageMetadataMap = hashMapOf(packageName to packageMetadata)
    private val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
    private val apkInputStream = ByteArrayInputStream(apkBytes)
    private val appName = getRandomString()
    private val installerName = packageMetadata.installer
    private val backup = RestorableBackup(metadata.copy(packageMetadataMap = packageMetadataMap))
    private val suffixName = getRandomString()

    init {
        // as we don't do strict signature checking, we can use a relaxed mock
        packageInfo.signingInfo = mockk(relaxed = true)
    }

    @Test
    fun `signature mismatch causes FAILED status`(@TempDir tmpDir: Path) = runBlocking {
        // change SHA256 signature to random
        val packageMetadata = packageMetadata.copy(sha256 = getRandomString())
        val backup = swapPackages(hashMapOf(packageName to packageMetadata))

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        every { crypto.getNameForApk(salt, packageName, "") } returns name
        coEvery { storagePlugin.getInputStream(token, name) } returns apkInputStream
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedFailFinished(i, value)
        }
    }

    @Test
    fun `package name mismatch causes FAILED status`(@TempDir tmpDir: Path) = runBlocking {
        // change package name to random string
        packageInfo.packageName = getRandomString()

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        every { crypto.getNameForApk(salt, packageName, "") } returns name
        coEvery { storagePlugin.getInputStream(token, name) } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedFailFinished(i, value)
        }
    }

    @Test
    fun `test apkInstaller throws exceptions`(@TempDir tmpDir: Path) = runBlocking {
        cacheBaseApkAndGetInfo(tmpDir)
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } throws SecurityException()
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressFailFinished(i, value)
        }
    }

    @Test
    fun `test successful run`(@TempDir tmpDir: Path) = runBlocking {
        val installResult = MutableInstallResult(1).apply {
            set(
                packageName, ApkInstallResult(
                    packageName,
                    progress = 1,
                    state = SUCCEEDED
                )
            )
        }

        cacheBaseApkAndGetInfo(tmpDir)
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } returns installResult
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressSuccessFinished(i, value)
        }
    }

    @Test
    fun `v0 test successful run`(@TempDir tmpDir: Path) = runBlocking {
        // This is a legacy backup with version 0
        val backup = backup.copy(backupMetadata = backup.backupMetadata.copy(version = 0))
        // Install will be successful
        val installResult = MutableInstallResult(1).apply {
            set(
                packageName, ApkInstallResult(
                    packageName,
                    progress = 1,
                    state = SUCCEEDED
                )
            )
        }

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        @Suppress("Deprecation")
        coEvery {
            legacyStoragePlugin.getApkInputStream(token, packageName, "")
        } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
        every { applicationInfo.loadIcon(pm) } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } returns installResult
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressSuccessFinished(i, value)
        }
    }

    @Test
    fun `test system apps only reinstalled when older system apps exist`(@TempDir tmpDir: Path) =
        runBlocking {
            val packageMetadata = this@ApkRestoreTest.packageMetadata.copy(system = true)
            packageMetadataMap[packageName] = packageMetadata
            val installedPackageInfo: PackageInfo = mockk()
            val willFail = Random.nextBoolean()
            val isSystemApp = Random.nextBoolean()

            cacheBaseApkAndGetInfo(tmpDir)
            every { storagePlugin.providerPackageName } returns storageProviderPackageName

            if (willFail) {
                every {
                    pm.getPackageInfo(packageName, 0)
                } throws PackageManager.NameNotFoundException()
            } else {
                installedPackageInfo.applicationInfo = mockk {
                    flags =
                        if (!isSystemApp) FLAG_INSTALLED else FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP
                }
                every { pm.getPackageInfo(packageName, 0) } returns installedPackageInfo
                every { installedPackageInfo.longVersionCode } returns packageMetadata.version!! - 1
                if (isSystemApp) { // if the installed app is not a system app, we don't install
                    val installResult = MutableInstallResult(1).apply {
                        set(
                            packageName,
                            ApkInstallResult(packageName, progress = 1, state = SUCCEEDED)
                        )
                    }
                    coEvery {
                        apkInstaller.install(
                            match { it.size == 1 },
                            packageName,
                            installerName,
                            any()
                        )
                    } returns installResult
                }
            }

            apkRestore.restore(backup).collectIndexed { i, value ->
                when (i) {
                    0 -> {
                        val result = value[packageName]
                        assertEquals(QUEUED, result.state)
                        assertEquals(1, result.progress)
                        assertEquals(1, value.total)
                    }
                    1 -> {
                        val result = value[packageName]
                        assertEquals(IN_PROGRESS, result.state)
                        assertEquals(appName, result.name)
                        assertEquals(icon, result.icon)
                    }
                    2 -> {
                        val result = value[packageName]
                        if (willFail) {
                            assertEquals(FAILED_SYSTEM_APP, result.state)
                        } else {
                            assertEquals(SUCCEEDED, result.state)
                        }
                    }
                    3 -> {
                        assertTrue(value.isFinished)
                    }
                    else -> fail("more values emitted")
                }
            }
        }

    @Test
    fun `incompatible splits cause FAILED state`(@TempDir tmpDir: Path) = runBlocking {
        // add one APK split to metadata
        val split1Name = getRandomString()
        val split2Name = getRandomString()
        packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
            splits = listOf(
                ApkSplit(split1Name, getRandomBase64()),
                ApkSplit(split2Name, getRandomBase64())
            )
        )

        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)

        // splits are NOT compatible
        every {
            splitCompatChecker.isCompatible(deviceName, listOf(split1Name, split2Name))
        } returns false
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressFailFinished(i, value)
        }
    }

    @Test
    fun `split signature mismatch causes FAILED state`(@TempDir tmpDir: Path) = runBlocking {
        // add one APK split to metadata
        val splitName = getRandomString()
        packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
            splits = listOf(ApkSplit(splitName, getRandomBase64(23)))
        )

        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)

        every { splitCompatChecker.isCompatible(deviceName, listOf(splitName)) } returns true
        every { crypto.getNameForApk(salt, packageName, splitName) } returns suffixName
        coEvery {
            storagePlugin.getInputStream(token, suffixName)
        } returns ByteArrayInputStream(getRandomByteArray())
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressFailFinished(i, value)
        }
    }

    @Test
    fun `exception while getting split data causes FAILED state`(@TempDir tmpDir: Path) =
        runBlocking {
            // add one APK split to metadata
            val splitName = getRandomString()
            val sha256 = getRandomBase64(23)
            packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
                splits = listOf(ApkSplit(splitName, sha256))
            )

            // cache APK and get icon as well as app name
            cacheBaseApkAndGetInfo(tmpDir)

            every { splitCompatChecker.isCompatible(deviceName, listOf(splitName)) } returns true
            every { crypto.getNameForApk(salt, packageName, splitName) } returns suffixName
            coEvery { storagePlugin.getInputStream(token, suffixName) } throws IOException()
            every { storagePlugin.providerPackageName } returns storageProviderPackageName

            apkRestore.restore(backup).collectIndexed { i, value ->
                assertQueuedProgressFailFinished(i, value)
            }
        }

    @Test
    fun `splits get installed along with base APK`(@TempDir tmpDir: Path) = runBlocking {
        // add one APK split to metadata
        val split1Name = getRandomString()
        val split2Name = getRandomString()
        val split1sha256 = "A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc-4E"
        val split2sha256 = "ZqZ1cVH47lXbEncWx-Pc4L6AdLZOIO2lQuXB5GypxB4"
        packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
            splits = listOf(
                ApkSplit(split1Name, split1sha256),
                ApkSplit(split2Name, split2sha256)
            )
        )

        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)

        every {
            splitCompatChecker.isCompatible(deviceName, listOf(split1Name, split2Name))
        } returns true

        // define bytes of splits and return them as stream (matches above hashes)
        val split1Bytes = byteArrayOf(0x01, 0x02, 0x03)
        val split2Bytes = byteArrayOf(0x07, 0x08, 0x09)
        val split1InputStream = ByteArrayInputStream(split1Bytes)
        val split2InputStream = ByteArrayInputStream(split2Bytes)
        val suffixName1 = getRandomString()
        val suffixName2 = getRandomString()
        every { crypto.getNameForApk(salt, packageName, split1Name) } returns suffixName1
        coEvery { storagePlugin.getInputStream(token, suffixName1) } returns split1InputStream
        every { crypto.getNameForApk(salt, packageName, split2Name) } returns suffixName2
        coEvery { storagePlugin.getInputStream(token, suffixName2) } returns split2InputStream
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        coEvery {
            apkInstaller.install(match { it.size == 3 }, packageName, installerName, any())
        } returns MutableInstallResult(1).apply {
            set(
                packageName, ApkInstallResult(
                    packageName,
                    progress = 1,
                    state = SUCCEEDED
                )
            )
        }

        apkRestore.restore(backup).collectIndexed { i, value ->
            assertQueuedProgressSuccessFinished(i, value)
        }
    }

    @Test
    fun `storage provider app does not get reinstalled`(@TempDir tmpDir: Path) = runBlocking {
        // set the storage provider package name to match our current package name,
        // and ensure that the current package is therefore skipped.
        every { storagePlugin.providerPackageName } returns packageName

        apkRestore.restore(backup).collectIndexed { i, value ->
            when (i) {
                0 -> {
                    assertFalse(value.isFinished)
                }
                1 -> {
                    // the only package provided should have been filtered, leaving 0 packages.
                    assertEquals(0, value.total)
                    assertTrue(value.isFinished)
                }
                else -> fail("more values emitted")
            }
        }
    }

    private fun swapPackages(packageMetadataMap: PackageMetadataMap): RestorableBackup {
        val metadata = metadata.copy(packageMetadataMap = packageMetadataMap)
        return backup.copy(backupMetadata = metadata)
    }

    private fun cacheBaseApkAndGetInfo(tmpDir: Path) {
        every { strictContext.cacheDir } returns File(tmpDir.toString())
        every { crypto.getNameForApk(salt, packageName, "") } returns name
        coEvery { storagePlugin.getInputStream(token, name) } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
        every { applicationInfo.loadIcon(pm) } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
    }

    private fun assertQueuedFailFinished(step: Int, value: InstallResult) = when (step) {
        0 -> assertQueuedProgress(step, value)
        1 -> {
            val result = value[packageName]
            assertEquals(FAILED, result.state)
            assertTrue(value.hasFailed)
            assertFalse(value.isFinished)
        }
        2 -> {
            assertTrue(value.hasFailed)
            assertTrue(value.isFinished)
        }
        else -> fail("more values emitted")
    }

    private fun assertQueuedProgressSuccessFinished(step: Int, value: InstallResult) = when (step) {
        0 -> assertQueuedProgress(step, value)
        1 -> assertQueuedProgress(step, value)
        2 -> {
            val result = value[packageName]
            assertEquals(SUCCEEDED, result.state)
        }
        3 -> {
            assertFalse(value.hasFailed)
            assertTrue(value.isFinished)
        }
        else -> fail("more values emitted")
    }

    private fun assertQueuedProgressFailFinished(step: Int, value: InstallResult) = when (step) {
        0 -> assertQueuedProgress(step, value)
        1 -> assertQueuedProgress(step, value)
        2 -> {
            // app install has failed
            val result = value[packageName]
            assertEquals(FAILED, result.state)
            assertTrue(value.hasFailed)
            assertFalse(value.isFinished)
        }
        3 -> {
            assertTrue(value.hasFailed)
            assertTrue(value.isFinished)
        }
        else -> fail("more values emitted")
    }

    private fun assertQueuedProgress(step: Int, value: InstallResult) = when (step) {
        0 -> {
            // single package gets queued
            val result = value[packageName]
            assertEquals(QUEUED, result.state)
            assertEquals(installerName, result.installerPackageName)
            assertEquals(1, result.progress)
            assertEquals(1, value.total)
        }
        1 -> {
            // name and icon are available now
            val result = value[packageName]
            assertEquals(IN_PROGRESS, result.state)
            assertEquals(appName, result.name)
            assertEquals(icon, result.icon)
            assertFalse(value.hasFailed)
        }
        else -> fail("more values emitted")
    }

}

private operator fun InstallResult.get(packageName: String): ApkInstallResult {
    return (this as MutableInstallResult)[packageName] ?: Assertions.fail("$packageName not found")
}
