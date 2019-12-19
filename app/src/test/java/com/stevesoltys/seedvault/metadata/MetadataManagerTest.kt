package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
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
    private val packageName = getRandomString()
    private val initialMetadata = BackupMetadata(token = time)
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
        every { metadataWriter.write(initialMetadata, storageOutputStream) } just Runs
        expectWriteToCache(initialMetadata)

        manager.onDeviceInitialization(storageOutputStream)

        assertEquals(time, manager.getBackupToken())
        assertEquals(0L, manager.getLastBackupTime())
    }

    @Test
    fun `test onApkBackedUp() with no prior package metadata`() {
        val packageMetadata = PackageMetadata(
                time = time + 1,
                version = Random.nextLong(Long.MAX_VALUE),
                installer = getRandomString(),
                signatures = listOf("sig")
        )

        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()
        every { clock.time() } returns time

        manager.onApkBackedUp(packageName, packageMetadata)

        assertEquals(packageMetadata, manager.getPackageMetadata(packageName))
    }

    @Test
    fun `test onApkBackedUp() with existing package metadata`() {
        val cachedMetadata = initialMetadata.copy()
        val packageMetadata = PackageMetadata(
                time = time,
                version = Random.nextLong(Long.MAX_VALUE),
                installer = getRandomString(),
                signatures = listOf("sig")
        )
        cachedMetadata.packageMetadata[packageName] = packageMetadata
        val updatedPackageMetadata = PackageMetadata(
                time = time + 1,
                version = packageMetadata.version!! + 1,
                installer = getRandomString(),
                signatures = listOf("sig foo")
        )

        expectReadFromCache(cachedMetadata)

        manager.onApkBackedUp(packageName, updatedPackageMetadata)

        assertEquals(updatedPackageMetadata, manager.getPackageMetadata(packageName))
    }

    @Test
    fun `test onPackageBackedUp()`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.time = time
        updatedMetadata.packageMetadata[packageName] = PackageMetadata(time)

        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()
        every { clock.time() } returns time
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } just Runs
        expectWriteToCache(updatedMetadata)

        manager.onPackageBackedUp(packageName, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
    }

    @Test
    fun `test onPackageBackedUp() fails to write to storage`() {
        val updateTime = time + 1
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.time = updateTime
        updatedMetadata.packageMetadata[packageName] = PackageMetadata(updateTime)

        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()
        every { clock.time() } returns time andThen updateTime
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } throws IOException()

        try {
            manager.onPackageBackedUp(packageName, storageOutputStream)
            fail()
        } catch (e: IOException) {
            // expected
        }

        assertEquals(0L, manager.getLastBackupTime())  // time was reverted
        assertEquals(initialMetadata.packageMetadata[packageName], manager.getPackageMetadata(packageName))
    }

    @Test
    fun `test onPackageBackedUp() with filled cache`() {
        val cachedPackageName = getRandomString()

        val cacheTime = time - 1
        val cachedMetadata = initialMetadata.copy(time = cacheTime)
        cachedMetadata.packageMetadata[cachedPackageName] = PackageMetadata(cacheTime)
        cachedMetadata.packageMetadata[packageName] = PackageMetadata(cacheTime)

        val updatedMetadata = cachedMetadata.copy(time = time)
        cachedMetadata.packageMetadata[cachedPackageName] = PackageMetadata(time)
        cachedMetadata.packageMetadata[packageName] = PackageMetadata(time)

        expectReadFromCache(cachedMetadata)
        every { clock.time() } returns time
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } just Runs
        expectWriteToCache(updatedMetadata)

        manager.onPackageBackedUp(packageName, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
        assertEquals(PackageMetadata(time), manager.getPackageMetadata(cachedPackageName))
        assertEquals(PackageMetadata(time), manager.getPackageMetadata(packageName))
    }

    private fun expectWriteToCache(metadata: BackupMetadata) {
        every { metadataWriter.encode(metadata) } returns encodedMetadata
        every { context.openFileOutput(METADATA_CACHE_FILE, MODE_PRIVATE) } returns cacheOutputStream
        every { cacheOutputStream.write(encodedMetadata) } just Runs
    }

    private fun expectReadFromCache(metadata: BackupMetadata) {
        val byteArray = ByteArray(DEFAULT_BUFFER_SIZE)
        every { context.openFileInput(METADATA_CACHE_FILE) } returns cacheInputStream
        every { cacheInputStream.available() } returns byteArray.size andThen 0
        every { cacheInputStream.read(byteArray) } returns -1
        every { metadataReader.decode(ByteArray(0)) } returns metadata
    }

}
