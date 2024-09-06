/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_TEST_ONLY
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.PackageUtils
import com.google.protobuf.ByteString.copyFromUtf8
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.SnapshotKt.app
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.transport.SnapshotManager
import com.stevesoltys.seedvault.transport.backup.AppBackupManager
import com.stevesoltys.seedvault.transport.backup.BackupData
import com.stevesoltys.seedvault.transport.backup.BackupReceiver
import com.stevesoltys.seedvault.transport.backup.BackupTest
import com.stevesoltys.seedvault.transport.backup.SnapshotCreator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

internal class ApkBackupTest : BackupTest() {

    private val pm: PackageManager = mockk()
    private val backupReceiver: BackupReceiver = mockk()
    private val appBackupManager: AppBackupManager = mockk()
    private val snapshotManager: SnapshotManager = mockk()
    private val snapshotCreator: SnapshotCreator = mockk()

    private val apkBackup =
        ApkBackup(pm, backupReceiver, appBackupManager, snapshotManager, settingsManager)

    private val signatureBytes = byteArrayOf(0x01, 0x02, 0x03)
    private val signatureHash = byteArrayOf(0x03, 0x02, 0x01)
    private val sigs = arrayOf(Signature(signatureBytes))

    init {
        mockkStatic(PackageUtils::class)
        every { appBackupManager.snapshotCreator } returns snapshotCreator
    }

    @Test
    fun `does not back up @pm@`() = runBlocking {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does not back up when setting disabled`() = runBlocking {
        every { settingsManager.backupApks() } returns false
        every { settingsManager.isBackupEnabled(any()) } returns true

        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does not back up when app blacklisted`() = runBlocking {
        every { settingsManager.backupApks() } returns true
        every { settingsManager.isBackupEnabled(any()) } returns false

        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does not back up test-only apps`() = runBlocking {
        packageInfo.applicationInfo!!.flags = FLAG_TEST_ONLY

        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does not back up system apps`() = runBlocking {
        packageInfo.applicationInfo!!.flags = FLAG_SYSTEM

        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does not back up the same version`() = runBlocking {
        packageInfo.applicationInfo!!.flags = FLAG_UPDATED_SYSTEM_APP
        val apk = apk.copy { versionCode = packageInfo.longVersionCode }
        val app = app { this.apk = apk }
        expectChecks(snapshot.toBuilder().putApps(packageInfo.packageName, app).build())

        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `does back up the same version when signatures changes`() {
        packageInfo.applicationInfo!!.sourceDir = "/tmp/doesNotExist"
        val apk = apk.copy {
            signatures.clear()
            signatures.add(copyFromUtf8("foo"))
            versionCode = packageInfo.longVersionCode
        }
        val app = app { this.apk = apk }
        expectChecks(snapshot.toBuilder().putApps(packageInfo.packageName, app).build())
        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, getRandomString())

        assertThrows(IOException::class.java) {
            runBlocking {
                apkBackup.backupApkIfNecessary(packageInfo)
            }
        }
        Unit
    }

    @Test
    fun `do not accept empty signature`() = runBlocking {
        every { settingsManager.backupApks() } returns true
        every { settingsManager.isBackupEnabled(any()) } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns emptyArray()

        apkBackup.backupApkIfNecessary(packageInfo)
    }

    @Test
    fun `test successful APK backup`(@TempDir tmpDir: Path) = runBlocking {
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo!!.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        val apkOutputStream = ByteArrayOutputStream()
        val installer = getRandomString()
        val capturedStream = slot<InputStream>()

        expectChecks()
        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, installer)
        coEvery { backupReceiver.readFromStream(capture(capturedStream)) } answers {
            capturedStream.captured.copyTo(apkOutputStream)
        }
        coEvery { backupReceiver.finalize() } returns BackupData(emptyList(), emptyMap())
        every {
            snapshotCreator.onApkBackedUp(packageInfo, match<Snapshot.Apk> {
                it.installer == installer
            }, emptyMap())
        } just Runs

        apkBackup.backupApkIfNecessary(packageInfo)
        assertArrayEquals(apkBytes, apkOutputStream.toByteArray())
    }

    @Test
    fun `test successful APK backup with two splits`(@TempDir tmpDir: Path) = runBlocking {
        // create base APK
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06) // not random because of hash
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo!!.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        // set split names
        val split1Name = "config.arm64_v8a"
        val split2Name = "config.xxxhdpi"
        packageInfo.splitNames = arrayOf(split1Name, split2Name)
        // create two split APKs
        val split1Bytes = byteArrayOf(0x07, 0x08, 0x09)
        val split2Bytes = byteArrayOf(0x01, 0x02, 0x03)
        packageInfo.applicationInfo!!.splitSourceDirs = arrayOf(
            File(tmpFile, "test-$split1Name.apk").apply {
                assertTrue(createNewFile())
                writeBytes(split1Bytes)
            }.absolutePath,
            File(tmpFile, "test-$split2Name.apk").apply {
                assertTrue(createNewFile())
                writeBytes(split2Bytes)
            }.absolutePath
        )
        // create streams
        val apkOutputStream = ByteArrayOutputStream()
        val split1OutputStream = ByteArrayOutputStream()
        val split2OutputStream = ByteArrayOutputStream()
        val capturedStream = slot<InputStream>()
        val installer = getRandomString()

        expectChecks()
        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, installer)
        coEvery { backupReceiver.readFromStream(capture(capturedStream)) } answers {
            capturedStream.captured.copyTo(apkOutputStream)
        } andThenAnswer {
            capturedStream.captured.copyTo(split1OutputStream)
        } andThenAnswer {
            capturedStream.captured.copyTo(split2OutputStream)
        }
        coEvery { backupReceiver.finalize() } returns BackupData(emptyList(), emptyMap())
        every {
            snapshotCreator.onApkBackedUp(packageInfo, match<Snapshot.Apk> {
                it.installer == installer &&
                    it.getSplits(1).name == split1Name &&
                    it.getSplits(2).name == split2Name
            }, emptyMap())
        } just Runs

        apkBackup.backupApkIfNecessary(packageInfo)
        assertArrayEquals(apkBytes, apkOutputStream.toByteArray())
        assertArrayEquals(split1Bytes, split1OutputStream.toByteArray())
        assertArrayEquals(split2Bytes, split2OutputStream.toByteArray())
    }

    private fun expectChecks(snapshot: Snapshot = this.snapshot) {
        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        every { snapshotManager.latestSnapshot } returns snapshot
        every { PackageUtils.computeSha256DigestBytes(signatureBytes) } returns signatureHash
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns sigs
    }

}
