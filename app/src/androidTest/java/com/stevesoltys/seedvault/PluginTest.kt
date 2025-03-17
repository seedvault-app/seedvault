/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import androidx.test.core.content.pm.PackageInfoBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.backend.saf.DocumentsProviderLegacyPlugin
import com.stevesoltys.seedvault.backend.saf.DocumentsStorage
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.BackendSaver
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.OutputStream

private const val root = ".SeedvaultPluginTest"

@RunWith(AndroidJUnit4::class)
@MediumTest
class PluginTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsManager: SettingsManager by inject()
    private val storage = DocumentsStorage(
        appContext = context,
        safStorage = settingsManager.getSafProperties() ?: error("No SAF storage"),
        root = root,
    )

    private val backend = SafBackend(context, storage.safStorage, root)

    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin = DocumentsProviderLegacyPlugin(context) {
        storage
    }

    private val token = System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L
    private val packageInfo = PackageInfoBuilder.newBuilder().setPackageName("org.example").build()
    private val packageInfo2 = PackageInfoBuilder.newBuilder().setPackageName("net.example").build()

    @Before
    fun setup() = runBlocking {
        backend.removeAll()
    }

    @After
    fun tearDown() = runBlocking {
        backend.removeAll()
    }

    @Test
    fun testProviderPackageName() {
        assertNotNull(backend.providerPackageName)
    }

    @Test
    fun testTest() = runBlocking(Dispatchers.IO) {
        assertTrue(backend.test())
    }

    @Test
    fun testGetFreeSpace() = runBlocking(Dispatchers.IO) {
        val freeBytes = backend.getFreeSpace() ?: error("no free space retrieved")
        assertTrue(freeBytes > 0)
    }

    /**
     * This test initializes the storage three times while creating two new restore sets.
     *
     * If this is run against a Nextcloud storage backend,
     * it has a high chance of getting a loading cursor in the underlying queries
     * that needs to get re-queried to get real results.
     */
    @Test
    fun testInitializationAndRestoreSets() = runBlocking(Dispatchers.IO) {
        // no backups available initially
        assertEquals(0, backend.getAvailableBackupFileHandles().toList().size)

        // write metadata (needed for backup to be recognized)
        backend.save(LegacyAppBackupFile.Metadata(token), getSaver(getRandomByteArray()))

        // one backup available now
        assertEquals(1, backend.getAvailableBackupFileHandles().toList().size)

        // initializing again (with another restore set) does add a restore set
        backend.save(LegacyAppBackupFile.Metadata(token + 1), getSaver(getRandomByteArray()))
        assertEquals(2, backend.getAvailableBackupFileHandles().toList().size)

        // initializing again (without new restore set) doesn't change number of restore sets
        backend.save(LegacyAppBackupFile.Metadata(token + 1), getSaver(getRandomByteArray()))
        assertEquals(2, backend.getAvailableBackupFileHandles().toList().size)
    }

    @Test
    fun testMetadataWriteRead() = runBlocking(Dispatchers.IO) {
        // write metadata
        val metadata = getRandomByteArray()
        backend.save(LegacyAppBackupFile.Metadata(token), getSaver(metadata))

        // get available backups, expect only one with our token and no error
        var availableBackups = backend.getAvailableBackupFileHandles().toList()
        assertEquals(1, availableBackups.size)
        var backupHandle = availableBackups[0] as LegacyAppBackupFile.Metadata
        assertEquals(token, backupHandle.token)

        // read metadata matches what was written earlier
        assertReadEquals(metadata, backend.load(backupHandle))

        // initializing again (without changing storage) keeps restore set with same token
        backend.save(LegacyAppBackupFile.Metadata(token), getSaver(metadata))
        availableBackups = backend.getAvailableBackupFileHandles().toList()
        assertEquals(1, availableBackups.size)
        backupHandle = availableBackups[0] as LegacyAppBackupFile.Metadata
        assertEquals(token, backupHandle.token)

        // metadata hasn't changed
        assertReadEquals(metadata, backend.load(backupHandle))
    }

    @Test
    fun v0testApkWriteRead() = runBlocking {
        // write random bytes as APK
        val apk1 = getRandomByteArray(1337 * 1024)
        backend.save(
            LegacyAppBackupFile.Blob(token, "${packageInfo.packageName}.apk"),
            getSaver(apk1)
        )

        // assert that read APK bytes match what was written
        assertReadEquals(
            apk1,
            legacyStoragePlugin.getApkInputStream(token, packageInfo.packageName, "")
        )

        // write random bytes as another APK
        val suffix2 = getRandomBase64(23)
        val apk2 = getRandomByteArray(23 * 1024 * 1024)

        backend.save(
            LegacyAppBackupFile.Blob(token, "${packageInfo2.packageName}$suffix2.apk"),
            getSaver(apk2)
        )

        // assert that read APK bytes match what was written
        assertReadEquals(
            apk2,
            legacyStoragePlugin.getApkInputStream(token, packageInfo2.packageName, suffix2)
        )
    }

    @Test
    fun testBackupRestore() = runBlocking {
        val name1 = getRandomBase64()
        val name2 = getRandomBase64()

        // write full backup data
        val data = getRandomByteArray(5 * 1024 * 1024)
        backend.save(LegacyAppBackupFile.Blob(token, name1), getSaver(data))

        // restore data matches backed up data
        assertReadEquals(data, backend.load(LegacyAppBackupFile.Blob(token, name1)))

        // write and check data for second package
        val data2 = getRandomByteArray(5 * 1024 * 1024)
        backend.save(LegacyAppBackupFile.Blob(token, name2), getSaver(data2))
        assertReadEquals(data2, backend.load(LegacyAppBackupFile.Blob(token, name2)))

        // remove data of first package again and ensure that no more data is found
        backend.remove(LegacyAppBackupFile.Blob(token, name1))

        // ensure that it gets deleted as well
        backend.remove(LegacyAppBackupFile.Blob(token, name2))
    }

    private fun getSaver(bytes: ByteArray) = object : BackendSaver {
        override val size: Long = bytes.size.toLong()
        override val sha256: String? = null

        override fun save(outputStream: OutputStream): Long {
            outputStream.write(bytes)
            return size
        }
    }

}
