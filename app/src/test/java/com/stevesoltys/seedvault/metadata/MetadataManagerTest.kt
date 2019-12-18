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

@RunWith(AndroidJUnit4::class)
class MetadataManagerTest {

    private val context: Context = mockk()
    private val clock: Clock = mockk()
    private val metadataWriter: MetadataWriter = mockk()
    private val metadataReader: MetadataReader = mockk()

    private val manager = MetadataManager(context, clock, metadataWriter, metadataReader)

    private val time = 42L
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
    fun `test onPackageBackedUp()`() {
        val packageName = getRandomString()
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
        val packageName = getRandomString()
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.time = time
        updatedMetadata.packageMetadata[packageName] = PackageMetadata(time)

        every { context.openFileInput(METADATA_CACHE_FILE) } throws FileNotFoundException()
        every { clock.time() } returns time
        every { metadataWriter.write(updatedMetadata, storageOutputStream) } throws IOException()

        try {
            manager.onPackageBackedUp(packageName, storageOutputStream)
            fail()
        } catch (e: IOException) {
            // expected
        }

        assertEquals(0L, manager.getLastBackupTime())  // time was reverted
        // TODO also assert reverted PackageMetadata once possible
    }

    @Test
    fun `test onPackageBackedUp() with filled cache`() {
        val cachedPackageName = getRandomString()
        val packageName = getRandomString()
        val byteArray = ByteArray(DEFAULT_BUFFER_SIZE)

        val cachedMetadata = initialMetadata.copy(time = 23)
        cachedMetadata.packageMetadata[cachedPackageName] = PackageMetadata(23)
        cachedMetadata.packageMetadata[packageName] = PackageMetadata(23)

        every { context.openFileInput(METADATA_CACHE_FILE) } returns cacheInputStream
        every { cacheInputStream.available() } returns byteArray.size andThen 0
        every { cacheInputStream.read(byteArray) } returns -1
        every { metadataReader.decode(ByteArray(0)) } returns cachedMetadata
        every { clock.time() } returns time
        every { metadataWriter.write(cachedMetadata, storageOutputStream) } just Runs
        expectWriteToCache(cachedMetadata)

        manager.onPackageBackedUp(packageName, storageOutputStream)

        assertEquals(time, manager.getLastBackupTime())
        // TODO also assert updated PackageMetadata once possible
    }

    private fun expectWriteToCache(metadata: BackupMetadata) {
        every { metadataWriter.encode(metadata) } returns encodedMetadata
        every { context.openFileOutput(METADATA_CACHE_FILE, MODE_PRIVATE) } returns cacheOutputStream
        every { cacheOutputStream.write(encodedMetadata) } just Runs
    }

}
