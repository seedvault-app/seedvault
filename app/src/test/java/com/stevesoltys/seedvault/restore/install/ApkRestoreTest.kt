/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.app.backup.IBackupManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.google.protobuf.ByteString.copyFrom
import com.google.protobuf.ByteString.fromHex
import com.stevesoltys.seedvault.BackupStateManager
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.proto.SnapshotKt.split
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.hexFromProto
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random

internal class ApkRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }
    private val backupManager: IBackupManager = mockk()
    private val backupStateManager: BackupStateManager = mockk()
    private val backendManager: BackendManager = mockk()
    private val loader: Loader = mockk()
    private val backend: Backend = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()
    private val installRestriction: InstallRestriction = mockk()

    private val apkRestore: ApkRestore = ApkRestore(
        context = strictContext,
        backupManager = backupManager,
        backupStateManager = backupStateManager,
        backendManager = backendManager,
        loader = loader,
        legacyStoragePlugin = mockk(),
        crypto = mockk(),
        splitCompatChecker = splitCompatChecker,
        apkInstaller = apkInstaller,
        installRestriction = installRestriction,
    )

    private val icon: Drawable = mockk()

    private val deviceName = metadata.deviceName
    private val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
    private val apkInputStream = ByteArrayInputStream(apkBytes)
    private val appName = getRandomString()
    private val appNoSplit = app.copy { // tests that need splits bring their own
        this.apk = apk.copy {
            splits.clear()
            splits.add(baseSplit)
        }
    }
    private val snapshotWithoutSplit = snapshot.copy {
        apps[packageName] = appNoSplit
    }
    private val packageMetadata = PackageMetadata.fromSnapshot(appNoSplit)
    private val packageMetadataMap: PackageMetadataMap = hashMapOf(packageName to packageMetadata)
    private val installerName = packageMetadata.installer
    private val backup = RestorableBackup(
        repoId = repoId,
        snapshot = snapshotWithoutSplit,
        backupMetadata = metadata.copy(packageMetadataMap = packageMetadataMap),
    )

    init {
        // as we don't do strict signature checking, we can use a relaxed mock
        packageInfo.signingInfo = mockk(relaxed = true)

        every { backendManager.backend } returns backend

        // related to starting/stopping service
        every { strictContext.packageName } returns "org.foo.bar"
        every {
            strictContext.startService(any())
        } returns ComponentName(strictContext, "org.foo.bar.Class")
        every { strictContext.stopService(any()) } returns true
    }

    @Test
    fun `test app without APK does not attempt install`(@TempDir tmpDir: Path) = runBlocking {
        // remove all APK info
        val packageMetadata = packageMetadata.copy(
            version = null,
            installer = null,
            sha256 = null,
            signatures = null,
        )
        val backup = swapPackages(hashMapOf(packageName to packageMetadata))

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertEquals(QUEUED, awaitItem()[packageName].state)
            assertEquals(FAILED, awaitItem()[packageName].state)
            assertTrue(awaitItem().isFinished)
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `test app without APK succeeds if installed`(@TempDir tmpDir: Path) = runBlocking {
        // remove all APK info
        val packageMetadata = packageMetadata.copy(
            version = null,
            installer = null,
            sha256 = null,
            signatures = null,
        )
        val backup = swapPackages(hashMapOf(packageName to packageMetadata))

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName

        val packageInfo: PackageInfo = mockk()
        every { pm.getPackageInfo(packageName, any<Int>()) } returns packageInfo
        every { packageInfo.longVersionCode } returns 42

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertEquals(QUEUED, awaitItem()[packageName].state)
            assertEquals(SUCCEEDED, awaitItem()[packageName].state)
            assertTrue(awaitItem().isFinished)
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `package name mismatch causes FAILED status`(@TempDir tmpDir: Path) = runBlocking {
        // change package name to random string
        packageInfo.packageName = getRandomString()

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { loader.loadFiles(listOf(blobHandle1)) } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedFailFinished()
        }
    }

    @Test
    fun `test apkInstaller throws exceptions`(@TempDir tmpDir: Path) = runBlocking {
        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        cacheBaseApkAndGetInfo(tmpDir)
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } throws SecurityException()
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedProgressFailFinished()
        }
    }

    @Test
    fun `test successful run`(@TempDir tmpDir: Path) = runBlocking {
        val packagesMap = mapOf(
            packageName to ApkInstallResult(
                packageName,
                state = SUCCEEDED,
                metadata = PackageMetadata(),
            )
        )
        val installResult = InstallResult(packagesMap)

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        cacheBaseApkAndGetInfo(tmpDir)
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } returns installResult
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedProgressSuccessFinished()
        }
    }

    @Test
    fun `test app only installed not already installed`(@TempDir tmpDir: Path) = runBlocking {
        val packageInfo: PackageInfo = mockk()
        mockkStatic("com.stevesoltys.seedvault.restore.install.ApkRestoreKt")
        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName
        every { pm.getPackageInfo(packageName, any<Int>()) } returns packageInfo
        every { packageInfo.signingInfo.getSignatures() } returns packageMetadata.signatures!!
        every {
            packageInfo.longVersionCode
        } returns packageMetadata.version!! + Random.nextLong(0, 2) // can be newer

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            awaitQueuedItem()
            awaitItem().also { systemItem ->
                val result = systemItem[packageName]
                assertEquals(SUCCEEDED, result.state)
            }
            awaitItem().also { finishedItem ->
                assertTrue(finishedItem.isFinished)
            }
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `test app still installed if older version is installed`(@TempDir tmpDir: Path) =
        runBlocking {
            val packageInfo: PackageInfo = mockk()
            mockkStatic("com.stevesoltys.seedvault.restore.install.ApkRestoreKt")
            every { installRestriction.isAllowedToInstallApks() } returns true
            every { backupStateManager.isAutoRestoreEnabled } returns false
            every { backend.providerPackageName } returns storageProviderPackageName
            every { pm.getPackageInfo(packageName, any<Int>()) } returns packageInfo
            every { packageInfo.signingInfo.getSignatures() } returns packageMetadata.signatures!!
            every { packageInfo.longVersionCode } returns packageMetadata.version!! - 1

            cacheBaseApkAndGetInfo(tmpDir)
            val packagesMap = mapOf(
                packageName to ApkInstallResult(
                    packageName,
                    state = SUCCEEDED,
                    metadata = PackageMetadata(),
                )
            )
            val installResult = InstallResult(packagesMap)
            coEvery {
                apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
            } returns installResult

            apkRestore.installResult.test {
                awaitItem() // initial empty state
                apkRestore.restore(backup)
                awaitQueuedItem()
                awaitInProgressItem()
                awaitItem().also { systemItem ->
                    val result = systemItem[packageName]
                    assertEquals(SUCCEEDED, result.state)
                }
                awaitItem().also { finishedItem ->
                    assertTrue(finishedItem.isFinished)
                }
                ensureAllEventsConsumed()
            }
        }

    @Test
    fun `test app fails if installed with different signer`(@TempDir tmpDir: Path) = runBlocking {
        val packageInfo: PackageInfo = mockk()
        mockkStatic("com.stevesoltys.seedvault.restore.install.ApkRestoreKt")
        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName
        every { pm.getPackageInfo(packageName, any<Int>()) } returns packageInfo
        every { packageInfo.signingInfo.getSignatures() } returns listOf("foobar")

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            awaitQueuedItem()
            awaitItem().also { systemItem ->
                val result = systemItem[packageName]
                assertEquals(FAILED, result.state)
            }
            awaitItem().also { finishedItem ->
                assertTrue(finishedItem.isFinished)
            }
            ensureAllEventsConsumed()
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

            every { installRestriction.isAllowedToInstallApks() } returns true
            every { backupStateManager.isAutoRestoreEnabled } returns false
            every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
            cacheBaseApkAndGetInfo(tmpDir)
            every { backend.providerPackageName } returns storageProviderPackageName

            if (willFail) {
                every {
                    pm.getPackageInfo(packageName, 0)
                } throws NameNotFoundException()
            } else {
                installedPackageInfo.applicationInfo = mockk {
                    flags =
                        if (!isSystemApp) FLAG_INSTALLED else FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP
                }
                every { pm.getPackageInfo(packageName, 0) } returns installedPackageInfo
                every { installedPackageInfo.longVersionCode } returns packageMetadata.version!! - 1
                if (isSystemApp) { // if the installed app is not a system app, we don't install
                    val packagesMap = mapOf(
                        packageName to ApkInstallResult(
                            packageName,
                            state = SUCCEEDED,
                            metadata = PackageMetadata(),
                        )
                    )
                    val installResult = InstallResult(packagesMap)
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

            apkRestore.installResult.test {
                awaitItem() // initial empty state
                apkRestore.restore(backup)
                awaitQueuedItem()
                awaitInProgressItem()
                awaitItem().also { systemItem ->
                    val result = systemItem[packageName]
                    if (willFail) {
                        assertEquals(FAILED_SYSTEM_APP, result.state)
                    } else {
                        assertEquals(SUCCEEDED, result.state)
                    }
                }
                awaitItem().also { finishedItem ->
                    assertTrue(finishedItem.isFinished)
                }
                ensureAllEventsConsumed()
            }
        }

    @Test
    fun `incompatible splits cause FAILED state`(@TempDir tmpDir: Path) = runBlocking {
        // add one APK split to metadata
        val split1Name = getRandomString()
        val split2Name = getRandomString()
        packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
            splits = listOf(
                ApkSplit(split1Name, Random.nextLong(), getRandomBase64()),
                ApkSplit(split2Name, Random.nextLong(), getRandomBase64())
            )
        )

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)

        // splits are NOT compatible
        every {
            splitCompatChecker.isCompatible(deviceName, listOf(split1Name, split2Name))
        } returns false
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedProgressFailFinished()
        }
    }

    @Test
    fun `exception while getting split data causes FAILED state`(@TempDir tmpDir: Path) =
        runBlocking {
            // add one APK split to metadata
            val splitName = getRandomString()
            val sha256 = getRandomBase64(23)
            val splitChunkId = Random.nextBytes(32).toHexString()
            val splitBlobId = Random.nextBytes(32).toHexString()
            val split = ApkSplit(splitName, Random.nextLong(), sha256, listOf(splitChunkId))
            packageMetadataMap[packageName] = packageMetadataMap[packageName]!!.copy(
                splits = listOf(split)
            )
            val blobHandle = AppBackupFileType.Blob(repoId, splitBlobId)
            val splitBlob = blob { id = fromHex(splitBlobId) }
            val snapshot = snapshot.toBuilder().putBlobs(splitChunkId, splitBlob).build()
            val backup = backup.copy(snapshot = snapshot)

            every { installRestriction.isAllowedToInstallApks() } returns true
            every { backupStateManager.isAutoRestoreEnabled } returns false
            every { backend.providerPackageName } returns storageProviderPackageName
            every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
            // cache APK and get icon as well as app name
            cacheBaseApkAndGetInfo(tmpDir)

            every { splitCompatChecker.isCompatible(deviceName, listOf(splitName)) } returns true
            coEvery { loader.loadFiles(listOf(blobHandle)) } throws IOException()

            apkRestore.installResult.test {
                awaitItem() // initial empty state
                apkRestore.restore(backup)
                assertQueuedProgressFailFinished()
            }
        }

    @Test
    fun `splits get installed along with base APK`(@TempDir tmpDir: Path) = runBlocking {
        // add one APK split to metadata
        val splitChunkId1 = Random.nextBytes(32).toHexString()
        val splitChunkId2 = Random.nextBytes(32).toHexString()
        val apkSplit1 = split {
            name = getRandomString()
            chunkIds.add(fromHex(splitChunkId1))
        }
        val apkSplit2 = split {
            name = getRandomString()
            chunkIds.add(fromHex(splitChunkId2))
        }
        val splitBlob1 = blob { id = copyFrom(Random.nextBytes(32)) }
        val splitBlob2 = blob { id = copyFrom(Random.nextBytes(32)) }
        val blobMap = apkBackupData.blobMap +
            mapOf(splitChunkId1 to splitBlob1) +
            mapOf(splitChunkId2 to splitBlob2)
        val app = appNoSplit.copy {
            this.apk = apk.copy {
                splits.clear()
                splits.addAll(listOf(baseSplit, apkSplit1, apkSplit2))
            }
        }
        val snapshot = snapshotWithoutSplit.copy {
            apps[packageName] = app
            blobs.putAll(blobMap)
        }
        packageMetadataMap[packageName] = PackageMetadata.fromSnapshot(app)
        val backup = backup.copy(snapshot = snapshot)

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)

        every {
            splitCompatChecker.isCompatible(deviceName, listOf(apkSplit1.name, apkSplit2.name))
        } returns true

        // define bytes of splits and return them as stream (matches above hashes)
        val split1Bytes = byteArrayOf(0x01, 0x02, 0x03)
        val split2Bytes = byteArrayOf(0x07, 0x08, 0x09)
        val split1InputStream = ByteArrayInputStream(split1Bytes)
        val split2InputStream = ByteArrayInputStream(split2Bytes)
        val splitHandle1 = AppBackupFileType.Blob(repoId, splitBlob1.id.hexFromProto())
        val splitHandle2 = AppBackupFileType.Blob(repoId, splitBlob2.id.hexFromProto())
        coEvery { loader.loadFiles(listOf(splitHandle1)) } returns split1InputStream
        coEvery { loader.loadFiles(listOf(splitHandle2)) } returns split2InputStream

        val resultMap = mapOf(
            packageName to ApkInstallResult(
                packageName,
                state = SUCCEEDED,
                metadata = PackageMetadata(),
            )
        )
        coEvery {
            apkInstaller.install(match { it.size == 3 }, packageName, installerName, any())
        } returns InstallResult(resultMap)

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedProgressSuccessFinished()
        }
    }

    @Test
    fun `storage provider app does not get reinstalled`() = runBlocking {
        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        // set the storage provider package name to match our current package name,
        // and ensure that the current package is therefore skipped.
        every { backend.providerPackageName } returns packageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            awaitItem().also { finishedItem ->
                // the only package provided should have been filtered, leaving 0 packages.
                assertEquals(0, finishedItem.total)
                assertTrue(finishedItem.isFinished)
            }
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `system app without APK get filtered out`() = runBlocking {
        // only backed up package is a system app without an APK
        packageMetadataMap[packageName] = PackageMetadata(
            time = 23L,
            system = true,
            isLaunchableSystemApp = Random.nextBoolean(),
        ).also { assertFalse(it.hasApk()) }

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns false
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)

            awaitItem().also { finishedItem ->
                println(finishedItem.installResults.values.toList())
                // the only package provided should have been filtered, leaving 0 packages.
                assertEquals(0, finishedItem.total)
                assertTrue(finishedItem.isFinished)
            }
            ensureAllEventsConsumed()
        }
    }

    @Test
    fun `auto restore gets turned off, if it was on`(@TempDir tmpDir: Path) = runBlocking {
        val packagesMap = mapOf(
            packageName to ApkInstallResult(
                packageName,
                state = SUCCEEDED,
                metadata = PackageMetadata(),
            )
        )
        val installResult = InstallResult(packagesMap)

        every { installRestriction.isAllowedToInstallApks() } returns true
        every { backupStateManager.isAutoRestoreEnabled } returns true
        every { backend.providerPackageName } returns storageProviderPackageName
        every { backupManager.setAutoRestore(false) } just Runs
        every { pm.getPackageInfo(packageName, any<Int>()) } throws NameNotFoundException()
        // cache APK and get icon as well as app name
        cacheBaseApkAndGetInfo(tmpDir)
        coEvery {
            apkInstaller.install(match { it.size == 1 }, packageName, installerName, any())
        } returns installResult
        every { backupManager.setAutoRestore(true) } just Runs

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            assertQueuedProgressSuccessFinished()
        }
        verifyOrder {
            backupManager.setAutoRestore(false)
            backupManager.setAutoRestore(true)
        }
    }

    @Test
    fun `no apks get installed when blocked by policy`() = runBlocking {
        every { installRestriction.isAllowedToInstallApks() } returns false
        every { backend.providerPackageName } returns storageProviderPackageName

        apkRestore.installResult.test {
            awaitItem() // initial empty state
            apkRestore.restore(backup)
            awaitItem().also { queuedItem ->
                // single package fails without attempting to install it
                assertEquals(1, queuedItem.total)
                assertEquals(FAILED, queuedItem[packageName].state)
                assertTrue(queuedItem.isFinished)
            }
            ensureAllEventsConsumed()
        }
    }

    private fun swapPackages(packageMetadataMap: PackageMetadataMap): RestorableBackup {
        val metadata = metadata.copy(packageMetadataMap = packageMetadataMap)
        return backup.copy(backupMetadata = metadata)
    }

    private fun cacheBaseApkAndGetInfo(tmpDir: Path) {
        every { strictContext.cacheDir } returns File(tmpDir.toString())
        coEvery { loader.loadFiles(listOf(blobHandle1)) } returns apkInputStream
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
        every { applicationInfo.loadIcon(pm) } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo!!) } returns appName
    }

    private suspend fun TurbineTestContext<InstallResult>.assertQueuedFailFinished() {
        awaitQueuedItem()
        awaitItem().also { item ->
            val result = item[packageName]
            assertEquals(IN_PROGRESS, result.state)
            assertFalse(item.hasFailed)
            assertEquals(1, item.total)
            assertEquals(1, item.list.size)
            assertNull(result.icon)
        }
        awaitItem().also { failedItem ->
            val result = failedItem[packageName]
            assertEquals(FAILED, result.state)
            assertTrue(failedItem.hasFailed)
            assertFalse(failedItem.isFinished)
        }
        awaitItem().also { finishedItem ->
            assertTrue(finishedItem.hasFailed)
            assertTrue(finishedItem.isFinished)
        }
        ensureAllEventsConsumed()
    }

    private suspend fun TurbineTestContext<InstallResult>.assertQueuedProgressSuccessFinished() {
        awaitQueuedItem()
        awaitInProgressItem()
        awaitItem().also { successItem ->
            val result = successItem[packageName]
            assertEquals(SUCCEEDED, result.state)
        }
        awaitItem().also { finishedItem ->
            assertFalse(finishedItem.hasFailed)
            assertTrue(finishedItem.isFinished)
        }
        ensureAllEventsConsumed()
    }

    private suspend fun TurbineTestContext<InstallResult>.assertQueuedProgressFailFinished() {
        awaitQueuedItem()
        awaitInProgressItem()
        awaitItem().also { failedItem ->
            // app install has failed
            val result = failedItem[packageName]
            assertEquals(FAILED, result.state)
            assertTrue(failedItem.hasFailed)
            assertFalse(failedItem.isFinished)
        }
        awaitItem().also { finishedItem ->
            assertTrue(finishedItem.hasFailed)
            assertTrue(finishedItem.isFinished)
        }
        ensureAllEventsConsumed()
    }

    private suspend fun TurbineTestContext<InstallResult>.awaitQueuedItem(): InstallResult {
        val item = awaitItem()
        // single package gets queued
        val result = item[packageName]
        assertEquals(QUEUED, result.state)
        assertEquals(installerName, result.installerPackageName)
        assertEquals(1, item.total)
        assertEquals(0, item.list.size) // all items still queued
        return item
    }

    private suspend fun TurbineTestContext<InstallResult>.awaitInProgressItem(): InstallResult {
        awaitItem().also { item ->
            val result = item[packageName]
            assertEquals(IN_PROGRESS, result.state)
            assertFalse(item.hasFailed)
            assertEquals(1, item.total)
            assertEquals(1, item.list.size)
            assertNull(result.icon)
        }
        val item = awaitItem()
        // name and icon are available now
        val result = item[packageName]
        assertEquals(IN_PROGRESS, result.state)
        assertEquals(appName, result.name)
        assertEquals(icon, result.icon)
        assertFalse(item.hasFailed)
        assertEquals(1, item.total)
        assertEquals(1, item.list.size)
        return item
    }

}

internal operator fun InstallResult.get(packageName: String): ApkInstallResult {
    return this.installResults[packageName] ?: Assertions.fail("$packageName not found")
}
