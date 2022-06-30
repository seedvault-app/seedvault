package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.util.PackageUtils
import com.stevesoltys.seedvault.assertReadEquals
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.ApkSplit
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.metadata.PackageState
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.restore.RestorableBackup
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.ApkBackup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
@Suppress("BlockingMethodInNonBlockingContext")
internal class ApkBackupRestoreTest : TransportTest() {

    private val pm: PackageManager = mockk()
    private val strictContext: Context = mockk<Context>().apply {
        every { packageManager } returns pm
    }

    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin = mockk()
    private val storagePlugin: StoragePlugin = mockk()
    private val splitCompatChecker: ApkSplitCompatibilityChecker = mockk()
    private val apkInstaller: ApkInstaller = mockk()

    private val apkBackup = ApkBackup(pm, crypto, settingsManager, metadataManager)
    private val apkRestore: ApkRestore = ApkRestore(
        context = strictContext,
        storagePlugin = storagePlugin,
        legacyStoragePlugin = legacyStoragePlugin,
        crypto = crypto,
        splitCompatChecker = splitCompatChecker,
        apkInstaller = apkInstaller
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
        splits = listOf(ApkSplit(splitName, splitSha256))
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

        apkBackup.backupApkIfNecessary(packageInfo, PackageState.APK_AND_DATA, outputStreamGetter)

        assertArrayEquals(apkBytes, outputStream.toByteArray())
        assertArrayEquals(splitBytes, splitOutputStream.toByteArray())

        val inputStream = ByteArrayInputStream(apkBytes)
        val splitInputStream = ByteArrayInputStream(splitBytes)
        val apkPath = slot<String>()
        val cacheFiles = slot<List<File>>()

        every { strictContext.cacheDir } returns tmpFile
        every { crypto.getNameForApk(salt, packageName, "") } returns name
        coEvery { storagePlugin.getInputStream(token, name) } returns inputStream
        every { pm.getPackageArchiveInfo(capture(apkPath), any()) } returns packageInfo
        every {
            @Suppress("UNRESOLVED_REFERENCE")
            pm.loadItemIcon(
                packageInfo.applicationInfo,
                packageInfo.applicationInfo
            )
        } returns icon
        every { pm.getApplicationLabel(packageInfo.applicationInfo) } returns appName
        every {
            splitCompatChecker.isCompatible(metadata.deviceName, listOf(splitName))
        } returns true
        every { crypto.getNameForApk(salt, packageName, splitName) } returns suffixName
        coEvery { storagePlugin.getInputStream(token, suffixName) } returns splitInputStream
        coEvery {
            apkInstaller.install(capture(cacheFiles), packageName, installerName, any())
        } returns MutableInstallResult(1).apply {
            set(
                packageName, ApkInstallResult(
                    packageName,
                    progress = 1,
                    state = ApkInstallState.SUCCEEDED
                )
            )
        }

        val backup = RestorableBackup(metadata.copy(packageMetadataMap = packageMetadataMap))
        apkRestore.restore(backup).collectIndexed { i, value ->
            assertFalse(value.hasFailed)
            assertEquals(1, value.total)
            if (i == 3) assertTrue(value.isFinished)
        }

        val apkFile = File(apkPath.captured)
        assertEquals(2, cacheFiles.captured.size)
        assertEquals(apkFile, cacheFiles.captured[0])
        val splitFile = cacheFiles.captured[1]
        assertReadEquals(apkBytes, FileInputStream(apkFile))
        assertReadEquals(splitBytes, FileInputStream(splitFile))
    }

}
