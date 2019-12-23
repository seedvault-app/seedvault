package com.stevesoltys.seedvault.transport.backup

import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.PackageUtils
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageMetadata
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import kotlin.random.Random


internal class ApkBackupTest : BackupTest() {

    private val pm: PackageManager = mockk()
    private val streamGetter: () -> OutputStream = mockk()

    private val apkBackup = ApkBackup(pm, clock, settingsManager, metadataManager)

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
    fun `does not back up @pm@`() {
        val packageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up when setting disabled`() {
        every { settingsManager.backupApks() } returns false

        assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up system apps`() {
        packageInfo.applicationInfo.flags = FLAG_SYSTEM

        every { settingsManager.backupApks() } returns true

        assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does not back up the same version`() {
        packageInfo.applicationInfo.flags = FLAG_UPDATED_SYSTEM_APP
        val packageMetadata = packageMetadata.copy(
                version = packageInfo.longVersionCode
        )

        expectChecks(packageMetadata)

        assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `does back up the same version when signatures changes`() {
        packageInfo.applicationInfo.sourceDir = "/tmp/doesNotExist"

        expectChecks()

        assertThrows(IOException::class.java) {
            assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
        }
    }

    @Test
    fun `do not accept empty signature`() {
        every { settingsManager.backupApks() } returns true
        every { metadataManager.getPackageMetadata(packageInfo.packageName) } returns packageMetadata
        every { sigInfo.hasMultipleSigners() } returns false
        every { sigInfo.signingCertificateHistory } returns emptyArray()

        assertFalse(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
    }

    @Test
    fun `test successful APK backup`(@TempDir tmpDir: Path) {
        val apkBytes = byteArrayOf(0x04, 0x05, 0x06)
        val tmpFile = File(tmpDir.toAbsolutePath().toString())
        packageInfo.applicationInfo.sourceDir = File(tmpFile, "test.apk").apply {
            assertTrue(createNewFile())
            writeBytes(apkBytes)
        }.absolutePath
        val apkOutputStream = ByteArrayOutputStream()
        val updatedMetadata = PackageMetadata(
                time = Random.nextLong(),
                version = packageInfo.longVersionCode,
                installer = getRandomString(),
                sha256 = "eHx5jjmlvBkQNVuubQzYejay4Q_QICqD47trAF2oNHI",
                signatures = packageMetadata.signatures
        )

        expectChecks()
        every { streamGetter.invoke() } returns apkOutputStream
        every { pm.getInstallerPackageName(packageInfo.packageName) } returns updatedMetadata.installer
        every { clock.time() } returns updatedMetadata.time
        every { metadataManager.onApkBackedUp(packageInfo.packageName, updatedMetadata) } just Runs

        assertTrue(apkBackup.backupApkIfNecessary(packageInfo, streamGetter))
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
