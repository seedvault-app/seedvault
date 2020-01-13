package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import java.io.*
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MetadataManagerTest {

    private val context: Context = mockk()
    private val clock: Clock = mockk()
    private val metadataWriter: MetadataWriter = mockk()
    private val metadataReader: MetadataReader = mockk()

    private val manager = MetadataManager(context, clock, metadataWriter, metadataReader)

    private val time = 42L
    private val token = Random.nextLong()
    private val packageName = getRandomString()
    private val initialMetadata = BackupMetadata(token = token)
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
        expectReadFromCache()
        expectModifyMetadata(initialMetadata)

        manager.onDeviceInitialization(token, storageOutputStream)

        assertEquals(token, manager.getBackupToken())
        assertEquals(0L, manager.getLastBackupTime())
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

        manager.onApkBackedUp(packageName, packageMetadata, storageOutputStream)

        assertEquals(packageMetadata, manager.getPackageMetadata(packageName))
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

        manager.onApkBackedUp(packageName, updatedPackageMetadata, storageOutputStream)

        assertEquals(updatedPackageMetadata, manager.getPackageMetadata(packageName))
    }

    @Test
    fun `test onPackageBackedUp()`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.time = time
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(time)

        expectReadFromCache()
        every { clock.time() } returns time
        expectModifyMetadata(updatedMetadata)

        manager.onPackageBackedUp(packageName, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
    }

    @Test
    fun `test onPackageBackedUp() fails to write to storage`() {
        val updateTime = time + 1
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.time = updateTime
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(updateTime)

        expectReadFromCache()
        every { clock.time() } returns updateTime
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } throws IOException()

        try {
            manager.onPackageBackedUp(packageName, storageOutputStream)
            fail()
        } catch (e: IOException) {
            // expected
        }

        assertEquals(0L, manager.getLastBackupTime())  // time was reverted
        assertEquals(initialMetadata.packageMetadataMap[packageName], manager.getPackageMetadata(packageName))
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
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(time, state = APK_AND_DATA)

        expectReadFromCache()
        every { clock.time() } returns time
        expectModifyMetadata(updatedMetadata)

        manager.onPackageBackedUp(packageName, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
        assertEquals(PackageMetadata(time), manager.getPackageMetadata(cachedPackageName))
        assertEquals(updatedMetadata.packageMetadataMap[packageName], manager.getPackageMetadata(packageName))
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
    }

    private fun expectModifyMetadata(metadata: BackupMetadata) {
        every { metadataWriter.write(metadata, storageOutputStream) } just Runs
        every { metadataWriter.encode(metadata) } returns encodedMetadata
        every { context.openFileOutput(METADATA_CACHE_FILE, MODE_PRIVATE) } returns cacheOutputStream
        every { cacheOutputStream.write(encodedMetadata) } just Runs
    }

    private fun expectReadFromCache() {
        val byteArray = ByteArray(DEFAULT_BUFFER_SIZE)
        every { context.openFileInput(METADATA_CACHE_FILE) } returns cacheInputStream
        every { cacheInputStream.available() } returns byteArray.size andThen 0
        every { cacheInputStream.read(byteArray) } returns -1
        every { metadataReader.decode(ByteArray(0)) } returns initialMetadata
    }

}
