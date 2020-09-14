package com.stevesoltys.seedvault.transport.backup

import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.PackageUtils
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
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
    private val streamGetter: suspend () -> OutputStream = mockk()

    private val apkBackup = ApkBackup(pm, settingsManager, metadataManager)

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
        assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
    }

    @Test
    fun `does not back up when setting disabled`() = runBlocking {
        every { settingsManager.backupApks() } returns false

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
    }

    @Test
    fun `does not back up system apps`() = runBlocking {
        packageInfo.applicationInfo.flags = FLAG_SYSTEM

        every { settingsManager.backupApks() } returns true

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
    }

    @Test
    fun `does not back up the same version`() = runBlocking {
        packageInfo.applicationInfo.flags = FLAG_UPDATED_SYSTEM_APP
        val packageMetadata = packageMetadata.copy(
            version = packageInfo.longVersionCode
        )

        expectChecks(packageMetadata)

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
    }

    @Test
    fun `does back up the same version when signatures changes`() {
        packageInfo.applicationInfo.sourceDir = "/tmp/doesNotExist"

        expectChecks()

        assertThrows(IOException::class.java) {
            runBlocking {
                assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
            }
        }
    }

    @Test
    fun `do not accept empty signature`() = runBlocking {
        every { settingsManager.backupApks() } returns true
        every { metadataManager.getPackageMetadata(packageInfo.packageName) } returns packageMetadata
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns emptyArray()

        assertNull(apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter))
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
            time = 0L,
            state = UNKNOWN_ERROR,
            version = packageInfo.longVersionCode,
            installer = getRandomString(),
            sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
            signatures = packageMetadata.signatures
        )

        expectChecks()
        coEvery { streamGetter.invoke() } returns apkOutputStream
        every {
            pm.getInstallSourceInfo(packageInfo.packageName)
        } returns InstallSourceInfo(null, null, null, updatedMetadata.installer)
        every {
            metadataManager.onApkBackedUp(
                packageInfo,
                updatedMetadata,
                outputStream
            )
        } just Runs

        assertEquals(
            updatedMetadata,
            apkBackup.backupApkIfNecessary(packageInfo, UNKNOWN_ERROR, streamGetter)
        )
        assertArrayEquals(apkBytes, apkOutputStream.toByteArray())
    }

    private fun expectChecks(packageMetadata: PackageMetadata = this.packageMetadata) {
        every { settingsManager.backupApks() } returns true
        every { metadataManager.getPackageMetadata(packageInfo.packageName) } returns packageMetadata
        every { PackageUtils.computeSha256DigestBytes(signatureBytes) } returns signatureHash
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns sigs
    }

}
