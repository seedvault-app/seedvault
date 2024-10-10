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
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.SnapshotCreator
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.repo.hexFromProto
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.worker.ApkBackup
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
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
import java.io.InputStream
import java.nio.file.Path

internal class ApkBackupRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }

    private val backendManager: BackendManager = mockk()
    private val backupManager: IBackupManager = mockk()
    private val backupStateManager: BackupStateManager = mockk()
    private val backupReceiver: BackupReceiver = mockk()
    private val appBackupManager: AppBackupManager = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val snapshotCreator: SnapshotCreator = mockk()
    private val loader: Loader = mockk()

    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin = mockk()
    private val backend: Backend = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()
    private val installRestriction: InstallRestriction = mockk()

    private val apkBackup = ApkBackup(pm, backupReceiver, appBackupManager, settingsManager)
    private val apkRestore: ApkRestore = ApkRestore(
        context = strictContext,
        backupManager = backupManager,
        backupStateManager = backupStateManager,
        backendManager = backendManager,
        loader = loader,
        legacyStoragePlugin = legacyStoragePlugin,
        crypto = crypto,
        splitCompatChecker = splitCompatChecker,
        apkInstaller = apkInstaller,
        installRestriction = installRestriction,
    )

    private val signatureBytes = byteArrayOf(0x01, 0x02, 0x03)
    private val signatureHash = byteArrayOf(0x03, 0x02, 0x01)
    private val sigs = arrayOf(Signature(signatureBytes))
    private val packageMetadataMap: PackageMetadataMap =
        hashMapOf(packageName to PackageMetadata.fromSnapshot(app))
    private val installerName = apk.installer
    private val icon: Drawable = mockk()
    private val appName = getRandomString()
    private val outputStream = ByteArrayOutputStream()
    private val splitOutputStream = ByteArrayOutputStream()

    init {
        mockkStatic(PackageUtils::class)
        every { backendManager.backend } returns backend
        every { appBackupManager.snapshotCreator } returns snapshotCreator
    }

    @Test
    fun `test backup and restore with a split`(@TempDir tmpDir: Path) = runBlocking {
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo!!.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        packageInfo.splitNames = arrayOf(splitName)
        packageInfo.applicationInfo!!.splitSourceDirs = arrayOf(File(tmpFile, "split.apk").apply {
            assertTrue(createNewFile())
            writeBytes(splitBytes)
        }.absolutePath)
        val capturedApkStream = slot<InputStream>()

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
        every { snapshotManager.latestSnapshot } returns snapshot
        every { pm.getInstallSourceInfo(packageInfo.packageName) } returns mockk(relaxed = true)
        coEvery { backupReceiver.readFromStream(any(), capture(capturedApkStream)) } answers {
            capturedApkStream.captured.copyTo(outputStream)
            apkBackupData
        } andThenAnswer {
            capturedApkStream.captured.copyTo(splitOutputStream)
            splitBackupData
        }
        every {
            snapshotCreator.onApkBackedUp(packageInfo, any<Snapshot.Apk>(), blobMap)
        } just Runs

        apkBackup.backupApkIfNecessary(packageInfo, snapshot)

        assertArrayEquals(apkBytes, outputStream.toByteArray())
        assertArrayEquals(splitBytes, splitOutputStream.toByteArray())

        val inputStream = ByteArrayInputStream(apkBytes)
        val splitInputStream = ByteArrayInputStream(splitBytes)
        val apkPath = slot<String>()
        val cacheFiles = slot<List<File>>()
        val repoId = getRandomString()
        val apkHandle = AppBackupFileType.Blob(repoId, blob1.id.hexFromProto())
        val splitHandle = AppBackupFileType.Blob(repoId, blob2.id.hexFromProto())

        every { backend.providerPackageName } returns storageProviderPackageName
        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        every { strictContext.cacheDir } returns tmpFile
        coEvery { loader.loadFiles(listOf(apkHandle)) } returns inputStream
        every { pm.getPackageArchiveInfo(capture(apkPath), any<Int>()) } returns packageInfo
        every { applicationInfo.loadIcon(pm) } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo!!) } returns appName
        every {
            splitCompatChecker.isCompatible(metadata.deviceName, listOf(splitName))
        } returns true
        coEvery { loader.loadFiles(listOf(splitHandle)) } returns splitInputStream
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

        val backup = RestorableBackup(
            backupMetadata = metadata.copy(packageMetadataMap = packageMetadataMap),
            repoId = repoId,
            snapshot = snapshot,
        )
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
