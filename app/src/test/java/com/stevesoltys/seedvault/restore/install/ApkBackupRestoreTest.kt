/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.app.backup.IBackupManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.util.PackageUtils
import app.cash.turbine.test
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.assertReadEquals
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.restore.RestorableBackup
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.worker.ApkBackup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.random.Random

@ExperimentalCoroutinesApi
internal class ApkBackupRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }

    private val storagePluginManager: StoragePluginManager = mockk()
    private val backupManager: IBackupManager = mockk()
    private val backupStateManager: BackupStateManager = mockk()

    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin = mockk()
    private val storagePlugin: StoragePlugin<*> = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()
    private val installRestriction: InstallRestriction = mockk()

    private val apkBackup = ApkBackup(pm, crypto, settingsManager, metadataManager)
    private val apkRestore: ApkRestore = ApkRestore(
        context = strictContext,
        backupManager = backupManager,
        backupStateManager = backupStateManager,
        pluginManager = storagePluginManager,
        legacyStoragePlugin = legacyStoragePlugin,
        crypto = crypto,
        splitCompatChecker = splitCompatChecker,
        apkInstaller = apkInstaller,
        installRestriction = installRestriction,
    )

    private val signatureBytes = byteArrayOf(0x01, 0x02, 0x03)
    private val signatureHash = byteArrayOf(0x03, 0x02, 0x01)
    private val sigs = arrayOf(Signature(signatureBytes))
    private val packageName: String = packageInfo.packageName
    private val splitName = getRandomString()
    private val splitBytes = byteArrayOf(0x07, 0x08, 0x09)
    private val splitSha256 = "ZqZ1cVH47lXbEncWx-Pc4L6AdLZOIO2lQuXB5GypxB4"
    private val packageMetadata = PackageMetadata(
        time = Random.nextLong(),
        version = packageInfo.longVersionCode - 1,
        installer = getRandomString(),
        sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
        signatures = listOf("AwIB"),
        splits = listOf(ApkSplit(splitName, Random.nextLong(), splitSha256))
    )
    private val packageMetadataMap: PackageMetadataMap = hashMapOf(packageName to packageMetadata)
    private val installerName = packageMetadata.installer
    private val icon: Drawable = mockk()
    private val appName = getRandomString()
    private val suffixName = getRandomString()
    private val outputStream = ByteArrayOutputStream()
    private val splitOutputStream = ByteArrayOutputStream()
    private val outputStreamGetter: suspend (name: String) -> OutputStream = { name ->
        if (name == this.name) outputStream else splitOutputStream
    }

    init {
        mockkStatic(PackageUtils::class)
        every { storagePluginManager.appPlugin } returns storagePlugin
    }

    @Test
    fun `test backup and restore with a split`(@TempDir tmpDir: Path) = runBlocking {
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        packageInfo.splitNames = arrayOf(splitName)
        packageInfo.applicationInfo.splitSourceDirs = arrayOf(File(tmpFile, "split.apk").apply {
            assertTrue(createNewFile())
            writeBytes(splitBytes)
        }.absolutePath)

        // related to starting/stopping service
        every { strictContext.packageName } returns "org.foo.bar"
        every {
            strictContext.startService(any())
        } returns ComponentName(strictContext, "org.foo.bar.Class")
        every { strictContext.stopService(any()) } returns true

        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns sigs
        every { PackageUtils.computeSha256DigestBytes(signatureBytes) } returns signatureHash
        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { pm.getInstallSourceInfo(packageInfo.packageName) } returns mockk(relaxed = true)
        every { metadataManager.salt } returns salt
        every { crypto.getNameForApk(salt, packageName) } returns name
        every { crypto.getNameForApk(salt, packageName, splitName) } returns suffixName
        every { storagePlugin.providerPackageName } returns storageProviderPackageName

        apkBackup.backupApkIfNecessary(packageInfo, outputStreamGetter)

        assertArrayEquals(apkBytes, outputStream.toByteArray())
        assertArrayEquals(splitBytes, splitOutputStream.toByteArray())

        val inputStream = ByteArrayInputStream(apkBytes)
        val splitInputStream = ByteArrayInputStream(splitBytes)
        val apkPath = slot<String>()
        val cacheFiles = slot<List<File>>()

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        every { strictContext.cacheDir } returns tmpFile
        every { crypto.getNameForApk(salt, packageName, "") } returns name
        coEvery { storagePlugin.getInputStream(token, name) } returns inputStream
        every { pm.getPackageArchiveInfo(capture(apkPath), any<Int>()) } returns packageInfo
        every { applicationInfo.loadIcon(pm) } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
        every {
            splitCompatChecker.isCompatible(metadata.deviceName, listOf(splitName))
        } returns true
        every { crypto.getNameForApk(salt, packageName, splitName) } returns suffixName
        coEvery { storagePlugin.getInputStream(token, suffixName) } returns splitInputStream
        val resultMap = mapOf(
            packageName to ApkInstallResult(
                packageName,
                state = SUCCEEDED,
                metadata = packageMetadataMap[packageName] ?: fail(),
            )
        )
        coEvery {
            apkInstaller.install(capture(cacheFiles), packageName, installerName, any())
        } returns InstallResult(resultMap)

        val backup = RestorableBackup(metadata.copy(packageMetadataMap = packageMetadataMap))
        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            awaitItem().also {
                assertFalse(it.hasFailed)
                assertEquals(1, it.total)
                assertEquals(0, it.list.size)
                assertEquals(QUEUED, it.installResults[packageName]?.state)
                assertFalse(it.isFinished)
            }
            awaitItem().also {
                assertFalse(it.hasFailed)
                assertEquals(1, it.total)
                assertEquals(1, it.list.size)
                assertEquals(IN_PROGRESS, it.installResults[packageName]?.state)
                assertFalse(it.isFinished)
            }
            awaitItem().also {
                assertFalse(it.hasFailed)
                assertEquals(1, it.total)
                assertEquals(1, it.list.size)
                assertEquals(IN_PROGRESS, it.installResults[packageName]?.state)
                assertFalse(it.isFinished)
            }
            awaitItem().also {
                assertFalse(it.hasFailed)
                assertEquals(1, it.total)
                assertEquals(1, it.list.size)
                assertEquals(SUCCEEDED, it.installResults[packageName]?.state)
                assertFalse(it.isFinished)
            }
            awaitItem().also {
                assertFalse(it.hasFailed)
                assertEquals(1, it.total)
                assertEquals(1, it.list.size)
                assertEquals(SUCCEEDED, it.installResults[packageName]?.state)
                assertTrue(it.isFinished)
            }
            ensureAllEventsConsumed()
        }

        val apkFile = File(apkPath.captured)
        assertEquals(2, cacheFiles.captured.size)
        assertEquals(apkFile, cacheFiles.captured[0])
        val splitFile = cacheFiles.captured[1]
        assertReadEquals(apkBytes, FileInputStream(apkFile))
        assertReadEquals(splitBytes, FileInputStream(splitFile))
    }

}
