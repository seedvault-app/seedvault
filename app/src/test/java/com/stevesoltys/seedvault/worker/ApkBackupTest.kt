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
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.transport.backup.BackupTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class ApkBackupTest : BackupTest() {

    private val pm: PackageManager = mockk()
    private val streamGetter: suspend (name: String) -> OutputStream = mockk()

    private val apkBackup = ApkBackup(pm, crypto, settingsManager, metadataManager)

    private val signatureBytes = byteArrayOf(0x01, 0x02, 0x03)
    private val signatureHash = byteArrayOf(0x03, 0x02, 0x01)
    private val sigs = arrayOf(Signature(signatureBytes))
    private val packageMetadata = PackageMetadata(
        time = Random.nextLong(),
        version = packageInfo.longVersionCode - 1,
        signatures = listOf("AwIB")
    )

    init {
        mockkStatic(PackageUtils::class)
    }

    @Test
    fun `does not back up @pm@`() = runBlocking {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up when setting disabled`() = runBlocking {
        every { settingsManager.backupApks() } returns false
        every { settingsManager.isBackupEnabled(any()) } returns true

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up when app blacklisted`() = runBlocking {
        every { settingsManager.backupApks() } returns true
        every { settingsManager.isBackupEnabled(any()) } returns false

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up test-only apps`() = runBlocking {
        packageInfo.applicationInfo.flags = FLAG_TEST_ONLY

        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up system apps`() = runBlocking {
        packageInfo.applicationInfo.flags = FLAG_SYSTEM

        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up the same version`() = runBlocking {
        packageInfo.applicationInfo.flags = FLAG_UPDATED_SYSTEM_APP
        val packageMetadata = packageMetadata.copy(
            version = packageInfo.longVersionCode
        )

        expectChecks(packageMetadata)

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does back up the same version when signatures changes`() {
        packageInfo.applicationInfo.sourceDir = "/tmp/doesNotExist"

        expectChecks()

        assertThrows(IOException::class.java) {
            runBlocking {
                assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
            }
        }
    }

    @Test
    fun `do not accept empty signature`() = runBlocking {
        every { settingsManager.backupApks() } returns true
        every { settingsManager.isBackupEnabled(any()) } returns true
        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns emptyArray()

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `test successful APK backup`(@TempDir tmpDir: Path) = runBlocking {
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        val apkOutputStream = ByteArrayOutputStream()
        val updatedMetadata = PackageMetadata(
            time = packageMetadata.time,
            state = UNKNOWN_ERROR,
            version = packageInfo.longVersionCode,
            installer = getRandomString(),
            sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
            signatures = packageMetadata.signatures
        )

        expectChecks()
        every { metadataManager.salt } returns salt
        every { crypto.getNameForApk(salt, packageInfo.packageName) } returns name
        coEvery { streamGetter.invoke(name) } returns apkOutputStream
        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, updatedMetadata.installer)

        assertEquals(
            updatedMetadata,
            apkBackup.backupApkIfNecessary(packageInfo, streamGetter)
        )
        assertArrayEquals(apkBytes, apkOutputStream.toByteArray())
    }

    @Test
    fun `test successful APK backup with two splits`(@TempDir tmpDir: Path) = runBlocking {
        // create base APK
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06) // not random because of hash
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        // set split names
        val split1Name = "config.arm64_v8a"
        val split2Name = "config.xxxhdpi"
        packageInfo.splitNames = arrayOf(split1Name, split2Name)
        // create two split APKs
        val split1Bytes = byteArrayOf(0x07, 0x08, 0x09)
        val split1Sha256 = "ZqZ1cVH47lXbEncWx-Pc4L6AdLZOIO2lQuXB5GypxB4"
        val split2Bytes = byteArrayOf(0x01, 0x02, 0x03)
        val split2Sha256 = "A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc-4E"
        packageInfo.applicationInfo.splitSourceDirs = arrayOf(
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
        // expected new metadata for package
        val updatedMetadata = PackageMetadata(
            time = packageMetadata.time,
            state = UNKNOWN_ERROR,
            version = packageInfo.longVersionCode,
            installer = getRandomString(),
            splits = listOf(
                ApkSplit(split1Name, split1Sha256),
                ApkSplit(split2Name, split2Sha256)
            ),
            sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
            signatures = packageMetadata.signatures
        )
        val suffixName1 = getRandomString()
        val suffixName2 = getRandomString()

        expectChecks()
        every { metadataManager.salt } returns salt
        every { crypto.getNameForApk(salt, packageInfo.packageName) } returns name
        every {
            crypto.getNameForApk(salt, packageInfo.packageName, split1Name)
        } returns suffixName1
        every {
            crypto.getNameForApk(salt, packageInfo.packageName, split2Name)
        } returns suffixName2
        coEvery { streamGetter.invoke(name) } returns apkOutputStream
        coEvery { streamGetter.invoke(suffixName1) } returns split1OutputStream
        coEvery { streamGetter.invoke(suffixName2) } returns split2OutputStream

        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, updatedMetadata.installer)

        assertEquals(
            updatedMetadata,
            apkBackup.backupApkIfNecessary(packageInfo, streamGetter)
        )
        assertArrayEquals(apkBytes, apkOutputStream.toByteArray())
        assertArrayEquals(split1Bytes, split1OutputStream.toByteArray())
        assertArrayEquals(split2Bytes, split2OutputStream.toByteArray())
    }

    private fun expectChecks(packageMetadata: PackageMetadata = this.packageMetadata) {
        every { settingsManager.isBackupEnabled(any()) } returns true
        every { settingsManager.backupApks() } returns true
        every {
            metadataManager.getPackageMetadata(packageInfo.packageName)
        } returns packageMetadata
        every { PackageUtils.computeSha256DigestBytes(signatureBytes) } returns signatureHash
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns sigs
    }

}
