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
import com.google.protobuf.ByteString
import com.google.protobuf.ByteString.copyFromUtf8
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.assertReadEquals
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.decodeBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.SnapshotManager
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.AppBackupManager
import com.stevesoltys.seedvault.transport.backup.BackupData
import com.stevesoltys.seedvault.transport.backup.BackupReceiver
import com.stevesoltys.seedvault.transport.backup.SnapshotCreator
import com.stevesoltys.seedvault.transport.backup.hexFromProto
import com.stevesoltys.seedvault.transport.restore.Loader
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.worker.ApkBackup
import com.stevesoltys.seedvault.worker.BASE_SPLIT
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.toHexString
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
import kotlin.random.Random

@ExperimentalCoroutinesApi
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

    private val apkBackup =
        ApkBackup(pm, backupReceiver, appBackupManager, snapshotManager, settingsManager)
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
    private val packageName: String = packageInfo.packageName
    private val splitName = getRandomString()
    private val splitBytes = byteArrayOf(0x07, 0x08, 0x09)
    private val apkChunkId = Random.nextBytes(32).toHexString()
    private val splitChunkId = Random.nextBytes(32).toHexString()
    private val apkBlob =
        Snapshot.Blob.newBuilder().setId(ByteString.copyFrom(Random.nextBytes(32))).build()
    private val splitBlob =
        Snapshot.Blob.newBuilder().setId(ByteString.copyFrom(Random.nextBytes(32))).build()
    private val apkBackupData = BackupData(listOf(apkChunkId), mapOf(apkChunkId to apkBlob))
    private val splitBackupData = BackupData(listOf(splitChunkId), mapOf(splitChunkId to splitBlob))
    private val chunkMap = apkBackupData.chunkMap + splitBackupData.chunkMap
    private val baseSplit = Snapshot.Split.newBuilder().setName(BASE_SPLIT)
        .addAllChunkIds(listOf(ByteString.fromHex(apkChunkId))).build()
    private val apkSplit = Snapshot.Split.newBuilder().setName(splitName)
        .addAllChunkIds(listOf(ByteString.fromHex(splitChunkId))).build()
    private val apk = Snapshot.Apk.newBuilder()
        .setVersionCode(packageInfo.longVersionCode - 1)
        .setInstaller(getRandomString())
        .addAllSignatures(mutableListOf(copyFromUtf8("AwIB".decodeBase64())))
        .addSplits(baseSplit)
        .addSplits(apkSplit)
        .build()
    private val app = Snapshot.App.newBuilder()
        .setApk(apk)
        .build()
    private val snapshot = Snapshot.newBuilder()
        .setToken(token)
        .putApps(packageName, app)
        .putAllBlobs(chunkMap)
        .build()
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
        coEvery { backupReceiver.readFromStream(capture(capturedApkStream)) } answers {
            capturedApkStream.captured.copyTo(outputStream)
        } andThenAnswer {
            capturedApkStream.captured.copyTo(splitOutputStream)
        }
        coEvery { backupReceiver.finalize() } returns apkBackupData andThen splitBackupData
        every {
            snapshotCreator.onApkBackedUp(packageInfo, any<Snapshot.Apk>(), chunkMap)
        } just Runs

        apkBackup.backupApkIfNecessary(packageInfo)

        assertArrayEquals(apkBytes, outputStream.toByteArray())
        assertArrayEquals(splitBytes, splitOutputStream.toByteArray())

        val inputStream = ByteArrayInputStream(apkBytes)
        val splitInputStream = ByteArrayInputStream(splitBytes)
        val apkPath = slot<String>()
        val cacheFiles = slot<List<File>>()
        val repoId = getRandomString()
        val apkHandle = AppBackupFileType.Blob(repoId, apkBlob.id.hexFromProto())
        val splitHandle = AppBackupFileType.Blob(repoId, splitBlob.id.hexFromProto())

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
