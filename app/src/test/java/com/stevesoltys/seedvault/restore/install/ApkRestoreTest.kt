package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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
import java.nio.file.Path
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
@ExperimentalCoroutinesApi
internal class ApkRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }
    private val restorePlugin: RestorePlugin = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()

    private val apkRestore: ApkRestore =
        ApkRestore(strictContext, restorePlugin, splitCompatChecker, apkInstaller)

    private val icon: Drawable = mockk()

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

    init {
        // as we don't do strict signature checking, we can use a relaxed mock
        packageInfo.signingInfo = mockk(relaxed = true)
    }

    @Test
    fun `signature mismatch causes FAILED status`(@TempDir tmpDir: Path) = runBlocking {
        // change SHA256 signature to random
        val packageMetadata = packageMetadata.copy(sha256 = getRandomString())
        val packageMetadataMap: PackageMetadataMap = hashMapOf(packageName to packageMetadata)

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { restorePlugin.getApkInputStream(token, packageName, "") } returns apkInputStream

        apkRestore.restore(token, packageMetadataMap).collectIndexed { index, value ->
            when (index) {
                0 -> {
                    val result = value[packageName]
                    assertEquals(QUEUED, result.state)
                    assertEquals(1, result.progress)
                    assertEquals(1, value.total)
                }
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
        }
    }

    @Test
    fun `package name mismatch causes FAILED status`(@TempDir tmpDir: Path) = runBlocking {
        // change package name to random string
        packageInfo.packageName = getRandomString()

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { restorePlugin.getApkInputStream(token, packageName, "") } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any()) } returns packageInfo

        apkRestore.restore(token, packageMetadataMap).collectIndexed { index, value ->
            when (index) {
                0 -> {
                    val result = value[packageName]
                    assertEquals(QUEUED, result.state)
                    assertEquals(1, result.progress)
                    assertEquals(1, value.total)
                }
                1 -> {
                    val result = value[packageName]
                    assertEquals(FAILED, result.state)
                    assertTrue(value.hasFailed)
                }
                2 -> {
                    assertTrue(value.hasFailed)
                    assertTrue(value.isFinished)
                }
                else -> fail("more values emitted")
            }
        }
    }

    @Test
    fun `test apkInstaller throws exceptions`(@TempDir tmpDir: Path) = runBlocking {
        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { restorePlugin.getApkInputStream(token, packageName, "") } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any()) } returns packageInfo
        every {
            pm.loadItemIcon(
                packageInfo.applicationInfo,
                packageInfo.applicationInfo
            )
        } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
        coEvery {
            apkInstaller.install(any(), packageName, installerName, any())
        } throws SecurityException()

        apkRestore.restore(token, packageMetadataMap).collectIndexed { index, value ->
            when (index) {
                0 -> {
                    val result = value[packageName]
                    assertEquals(QUEUED, result.state)
                    assertEquals(1, result.progress)
                    assertEquals(installerName, result.installerPackageName)
                    assertEquals(1, value.total)
                }
                1 -> {
                    val result = value[packageName]
                    assertEquals(IN_PROGRESS, result.state)
                    assertEquals(appName, result.name)
                    assertEquals(icon, result.icon)
                    assertFalse(value.hasFailed)
                }
                2 -> {
                    val result = value[packageName]
                    assertTrue(value.hasFailed)
                    assertEquals(FAILED, result.state)
                    assertFalse(value.isFinished)
                }
                3 -> {
                    assertTrue(value.hasFailed, "1")
                    assertTrue(value.isFinished, "2")
                }
                else -> fail("more values emitted")
            }
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

        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { restorePlugin.getApkInputStream(token, packageName, "") } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any()) } returns packageInfo
        every {
            pm.loadItemIcon(
                packageInfo.applicationInfo,
                packageInfo.applicationInfo
            )
        } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
        coEvery {
            apkInstaller.install(any(), packageName, installerName, any())
        } returns installResult

        var i = 0
        apkRestore.restore(token, packageMetadataMap).collect { value ->
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
                    assertEquals(SUCCEEDED, result.state)
                }
                3 -> {
                    assertFalse(value.hasFailed)
                    assertTrue(value.isFinished)
                }
                else -> fail("more values emitted")
            }
            i++
        }
    }

    @Test
    fun `test system apps only reinstalled when older system apps exist`(@TempDir tmpDir: Path) =
        runBlocking {
            val packageMetadata = this@ApkRestoreTest.packageMetadata.copy(system = true)
            packageMetadataMap[packageName] = packageMetadata
            packageInfo.applicationInfo = mockk()
            val installedPackageInfo: PackageInfo = mockk()
            val willFail = Random.nextBoolean()
            val isSystemApp = Random.nextBoolean()

            every { strictContext.cacheDir } returns File(tmpDir.toString())
            coEvery {
                restorePlugin.getApkInputStream(token, packageName, "")
            } returns apkInputStream
            every { pm.getPackageArchiveInfo(any(), any()) } returns packageInfo
            every {
                pm.loadItemIcon(
                    packageInfo.applicationInfo,
                    packageInfo.applicationInfo
                )
            } returns icon
            every { packageInfo.applicationInfo.loadIcon(pm) } returns icon
            every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
            if (willFail) {
                every {
                    pm.getPackageInfo(packageName, 0)
                } throws PackageManager.NameNotFoundException()
            } else {
                installedPackageInfo.applicationInfo = ApplicationInfo().apply {
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
                        apkInstaller.install(any(), packageName, installerName, any())
                    } returns installResult
                }
            }

            apkRestore.restore(token, packageMetadataMap).collectIndexed { i, value ->
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

}

private operator fun InstallResult.get(packageName: String): ApkInstallResult {
    return (this as MutableInstallResult)[packageName] ?: Assertions.fail("$packageName not found")
}
