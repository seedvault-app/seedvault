package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [32], // robolectric does not support 33, yet
    application = TestApp::class
)
class MetadataManagerTest {

    private val context: Context = mockk()
    private val clock: Clock = mockk()
    private val crypto: Crypto = mockk()
    private val metadataWriter: MetadataWriter = mockk()
    private val metadataReader: MetadataReader = mockk()

    private val manager = MetadataManager(context, clock, crypto, metadataWriter, metadataReader)

    private val time = 42L
    private val token = Random.nextLong()
    private val packageName = getRandomString()
    private val packageInfo = PackageInfo().apply {
        packageName = this@MetadataManagerTest.packageName
        applicationInfo = ApplicationInfo().apply { flags = FLAG_ALLOW_BACKUP }
    }
    private val saltBytes = Random.nextBytes(METADATA_SALT_SIZE)
    private val salt = saltBytes.encodeBase64()
    private val initialMetadata = BackupMetadata(token = token, salt = salt)
    private val storageOutputStream = ByteArrayOutputStream()
    private val cacheOutputStream: FileOutputStream = mockk()
    private val cacheInputStream: FileInputStream = mockk()
    private val encodedMetadata = getRandomByteArray()

    @After
    fun afterEachTest() {
        stopKoin()
    }

    @Test
    fun `test onDeviceInitialization()`() {
        every { clock.time() } returns time
        every { crypto.getRandomBytes(METADATA_SALT_SIZE) } returns saltBytes
        expectReadFromCache()
        expectModifyMetadata(initialMetadata)

        manager.onDeviceInitialization(token, storageOutputStream)

        assertEquals(token, manager.getBackupToken())
        assertEquals(0L, manager.getLastBackupTime())

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onApkBackedUp() with no prior package metadata`() {
        val packageMetadata = PackageMetadata(
            time = 0L,
            version = Random.nextLong(Long.MAX_VALUE),
            installer = getRandomString(),
            signatures = listOf("sig")
        )

        expectReadFromCache()
        expectModifyMetadata(initialMetadata)

        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)

        assertEquals(packageMetadata, manager.getPackageMetadata(packageName))

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onApkBackedUp() sets system metadata`() {
        packageInfo.applicationInfo = ApplicationInfo().apply { flags = FLAG_SYSTEM }
        val packageMetadata = PackageMetadata(
            time = 0L,
            version = Random.nextLong(Long.MAX_VALUE),
            installer = getRandomString(),
            signatures = listOf("sig")
        )

        expectReadFromCache()
        expectModifyMetadata(initialMetadata)

        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)

        assertEquals(packageMetadata.copy(system = true), manager.getPackageMetadata(packageName))

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onApkBackedUp() with existing package metadata`() {
        val packageMetadata = PackageMetadata(
            time = time,
            version = Random.nextLong(Long.MAX_VALUE),
            installer = getRandomString(),
            signatures = listOf("sig")
        )
        initialMetadata.packageMetadataMap[packageName] = packageMetadata
        val updatedPackageMetadata = PackageMetadata(
            time = time,
            version = packageMetadata.version!! + 1,
            installer = getRandomString(),
            signatures = listOf("sig foo")
        )

        expectReadFromCache()
        expectModifyMetadata(initialMetadata)

        manager.onApkBackedUp(packageInfo, updatedPackageMetadata, storageOutputStream)

        assertEquals(updatedPackageMetadata, manager.getPackageMetadata(packageName))

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onApkBackedUp() limits state changes`() {
        var version = Random.nextLong(Long.MAX_VALUE)
        var packageMetadata = PackageMetadata(
            version = version,
            installer = getRandomString(),
            signatures = listOf("sig")
        )

        expectReadFromCache()
        expectModifyMetadata(initialMetadata)
        val oldState = UNKNOWN_ERROR

        // state doesn't change for APK_AND_DATA
        packageMetadata = packageMetadata.copy(version = ++version, state = APK_AND_DATA)
        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)
        assertEquals(
            packageMetadata.copy(state = oldState),
            manager.getPackageMetadata(packageName)
        )

        // state doesn't change for QUOTA_EXCEEDED
        packageMetadata = packageMetadata.copy(version = ++version, state = QUOTA_EXCEEDED)
        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)
        assertEquals(
            packageMetadata.copy(state = oldState),
            manager.getPackageMetadata(packageName)
        )

        // state doesn't change for NO_DATA
        packageMetadata = packageMetadata.copy(version = ++version, state = NO_DATA)
        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)
        assertEquals(
            packageMetadata.copy(state = oldState),
            manager.getPackageMetadata(packageName)
        )

        // state DOES change for NOT_ALLOWED
        packageMetadata = packageMetadata.copy(version = ++version, state = NOT_ALLOWED)
        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)
        assertEquals(
            packageMetadata.copy(state = NOT_ALLOWED),
            manager.getPackageMetadata(packageName)
        )

        // state DOES change for WAS_STOPPED
        packageMetadata = packageMetadata.copy(version = ++version, state = WAS_STOPPED)
        manager.onApkBackedUp(packageInfo, packageMetadata, storageOutputStream)
        assertEquals(
            packageMetadata.copy(state = WAS_STOPPED),
            manager.getPackageMetadata(packageName)
        )

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onPackageBackedUp()`() {
        packageInfo.applicationInfo.flags = FLAG_SYSTEM
        val updatedMetadata = initialMetadata.copy(
            time = time,
            packageMetadataMap = PackageMetadataMap() // otherwise this isn't copied, but referenced
        )
        val packageMetadata = PackageMetadata(time)
        updatedMetadata.packageMetadataMap[packageName] = packageMetadata

        expectReadFromCache()
        every { clock.time() } returns time
        expectModifyMetadata(initialMetadata)

        manager.onPackageBackedUp(packageInfo, BackupType.FULL, storageOutputStream)

        assertEquals(
            packageMetadata.copy(state = APK_AND_DATA, backupType = BackupType.FULL, system = true),
            manager.getPackageMetadata(packageName)
        )
        assertEquals(time, manager.getLastBackupTime())

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onPackageBackedUp() fails to write to storage`() {
        val updateTime = time + 1
        val updatedMetadata = initialMetadata.copy(
            time = updateTime,
            packageMetadataMap = PackageMetadataMap() // otherwise this isn't copied, but referenced
        )
        updatedMetadata.packageMetadataMap[packageName] =
            PackageMetadata(updateTime, APK_AND_DATA, BackupType.KV)

        expectReadFromCache()
        every { clock.time() } returns updateTime
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } throws IOException()

        try {
            manager.onPackageBackedUp(packageInfo, BackupType.KV, storageOutputStream)
            fail()
        } catch (e: IOException) {
            // expected
        }

        assertEquals(0L, manager.getLastBackupTime()) // time was reverted
        assertEquals(
            initialMetadata.packageMetadataMap[packageName],
            manager.getPackageMetadata(packageName)
        )

        verify { cacheInputStream.close() }
    }

    @Test
    fun `test onPackageBackedUp() with filled cache`() {
        val cachedPackageName = getRandomString()

        val cacheTime = time - 1
        val cachedMetadata = initialMetadata.copy(time = cacheTime)
        cachedMetadata.packageMetadataMap[cachedPackageName] = PackageMetadata(cacheTime)
        cachedMetadata.packageMetadataMap[packageName] = PackageMetadata(cacheTime)

        val updatedMetadata = cachedMetadata.copy(time = time)
        updatedMetadata.packageMetadataMap[cachedPackageName] = PackageMetadata(time)
        updatedMetadata.packageMetadataMap[packageName] =
            PackageMetadata(time, state = APK_AND_DATA)

        expectReadFromCache()
        every { clock.time() } returns time
        expectModifyMetadata(updatedMetadata)

        manager.onPackageBackedUp(packageInfo, BackupType.FULL, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
        assertEquals(PackageMetadata(time), manager.getPackageMetadata(cachedPackageName))
        assertEquals(
            updatedMetadata.packageMetadataMap[packageName],
            manager.getPackageMetadata(packageName)
        )

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test getBackupToken() on first run`() {
        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()

        assertEquals(0L, manager.getBackupToken())
    }

    @Test
    fun `test getLastBackupTime() on first run`() {
        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()

        assertEquals(0L, manager.getLastBackupTime())
    }

    @Test
    fun `test getLastBackupTime() and getBackupToken() with cached metadata`() {
        initialMetadata.time = Random.nextLong()

        expectReadFromCache()

        assertEquals(initialMetadata.time, manager.getLastBackupTime())
        assertEquals(initialMetadata.token, manager.getBackupToken())

        verify { cacheInputStream.close() }
    }

    private fun expectModifyMetadata(metadata: BackupMetadata) {
        every { metadataWriter.write(metadata, storageOutputStream) } just Runs
        every { metadataWriter.encode(metadata) } returns encodedMetadata
        every {
            context.openFileOutput(
                METADATA_CACHE_FILE,
                MODE_PRIVATE
            )
        } returns cacheOutputStream
        every { cacheOutputStream.write(encodedMetadata) } just Runs
        every { cacheOutputStream.close() } just Runs
    }

    private fun expectReadFromCache() {
        val byteArray = ByteArray(DEFAULT_BUFFER_SIZE)
        every { context.openFileInput(METADATA_CACHE_FILE) } returns cacheInputStream
        every { cacheInputStream.available() } returns byteArray.size andThen 0
        every { cacheInputStream.read(byteArray) } returns -1
        every { metadataReader.decode(ByteArray(0)) } returns initialMetadata
        every { cacheInputStream.close() } just Runs
    }

}
