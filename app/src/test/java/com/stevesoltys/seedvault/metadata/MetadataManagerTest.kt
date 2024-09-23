/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34], // TODO: Drop once robolectric supports 35
    application = TestApp::class
)
class MetadataManagerTest {

    private val context: Context = mockk()
    private val clock: Clock = mockk()
    private val metadataWriter: MetadataWriter = mockk()
    private val metadataReader: MetadataReader = mockk()

    private val manager = MetadataManager(
        context = context,
        clock = clock,
        metadataWriter = metadataWriter,
        metadataReader = metadataReader,
    )

    private val packageManager: PackageManager = mockk()

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
    private val cacheOutputStream: FileOutputStream = mockk()
    private val cacheInputStream: FileInputStream = mockk()
    private val encodedMetadata = getRandomByteArray()

    @After
    fun afterEachTest() {
        stopKoin()
    }

    @Test
    fun `test onPackageBackedUp()`() {
        val updatedMetadata = initialMetadata.copy(
            time = time,
            packageMetadataMap = PackageMetadataMap() // otherwise this isn't copied, but referenced
        )
        val size = Random.nextLong()
        val packageMetadata = PackageMetadata(time)
        updatedMetadata.packageMetadataMap[packageName] = packageMetadata

        every { context.packageManager } returns packageManager
        expectReadFromCache()
        every { clock.time() } returns time
        expectWriteToCache(initialMetadata)

        manager.onPackageBackedUp(packageInfo, BackupType.FULL, size)

        assertEquals(
            packageMetadata.copy(
                state = APK_AND_DATA,
                backupType = BackupType.FULL,
                size = size,
            ),
            manager.getPackageMetadata(packageName)
        )
        assertFalse(updatedMetadata.d2dBackup)

        verify {
            cacheInputStream.close()
            cacheOutputStream.close()
        }
    }

    @Test
    fun `test onPackageBackedUp() with filled cache`() {
        val cachedPackageName = getRandomString()

        val cacheTime = time - 1
        val cachedMetadata = initialMetadata.copy()
        cachedMetadata.packageMetadataMap[cachedPackageName] = PackageMetadata(cacheTime)
        cachedMetadata.packageMetadataMap[packageName] = PackageMetadata(cacheTime)

        val updatedMetadata = cachedMetadata.copy()
        updatedMetadata.packageMetadataMap[cachedPackageName] = PackageMetadata(time)
        updatedMetadata.packageMetadataMap[packageName] =
            PackageMetadata(time, state = APK_AND_DATA)

        expectReadFromCache()
        every { context.packageManager } returns packageManager
        every { clock.time() } returns time
        expectWriteToCache(updatedMetadata)

        manager.onPackageBackedUp(packageInfo, BackupType.FULL, 0L)

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
    fun `test onPackageDoesNotGetBackedUp() updates state`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(state = NOT_ALLOWED)

        every { context.packageManager } returns packageManager
        expectReadFromCache()
        expectWriteToCache(updatedMetadata)

        manager.onPackageDoesNotGetBackedUp(packageInfo, NOT_ALLOWED)

        assertEquals(
            updatedMetadata.packageMetadataMap[packageName],
            manager.getPackageMetadata(packageName),
        )
    }

    @Test
    fun `test onPackageDoesNotGetBackedUp() creates new state`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(state = WAS_STOPPED)
        initialMetadata.packageMetadataMap.remove(packageName)

        every { context.packageManager } returns packageManager
        expectReadFromCache()
        expectWriteToCache(updatedMetadata)

        manager.onPackageDoesNotGetBackedUp(packageInfo, WAS_STOPPED)

        assertEquals(
            updatedMetadata.packageMetadataMap[packageName],
            manager.getPackageMetadata(packageName),
        )
    }

    @Test
    fun `test onPackageBackupError() updates state`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(state = NO_DATA)

        expectReadFromCache()
        expectWriteToCache(updatedMetadata)

        manager.onPackageBackupError(packageInfo, NO_DATA, BackupType.KV)
    }

    @Test
    fun `test onPackageBackupError() inserts new package`() {
        val updatedMetadata = initialMetadata.copy()
        updatedMetadata.packageMetadataMap[packageName] = PackageMetadata(state = WAS_STOPPED)
        initialMetadata.packageMetadataMap.remove(packageName)

        every { context.packageManager } returns packageManager
        expectReadFromCache()
        expectWriteToCache(updatedMetadata)

        manager.onPackageBackupError(packageInfo, WAS_STOPPED)
    }

    private fun expectReadFromCache() {
        val byteArray = ByteArray(DEFAULT_BUFFER_SIZE)
        every { context.openFileInput(METADATA_CACHE_FILE) } returns cacheInputStream
        every { cacheInputStream.available() } returns byteArray.size andThen 0
        every { cacheInputStream.read(byteArray) } returns -1
        every { metadataReader.decode(ByteArray(0)) } returns initialMetadata
        every { cacheInputStream.close() } just Runs
    }

    private fun expectWriteToCache(metadata: BackupMetadata) {
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

}
