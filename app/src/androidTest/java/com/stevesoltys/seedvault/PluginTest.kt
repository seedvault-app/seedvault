/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.net.Uri
import androidx.test.core.content.pm.PackageInfoBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderLegacyPlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderStoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.plugins.saf.deleteContents
import com.stevesoltys.seedvault.settings.SettingsManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
@MediumTest
class PluginTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsManager: SettingsManager by inject()
    private val mockedSettingsManager: SettingsManager = mockk()
    private val storage = DocumentsStorage(
        appContext = context,
        settingsManager = mockedSettingsManager,
        safStorage = settingsManager.getSafStorage() ?: error("No SAF storage"),
    )

    private val storagePlugin: StoragePlugin<Uri> = DocumentsProviderStoragePlugin(context, storage)

    @Suppress("Deprecation")
    private val legacyStoragePlugin: LegacyStoragePlugin = DocumentsProviderLegacyPlugin(context) {
        storage
    }

    private val token = System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L
    private val packageInfo = PackageInfoBuilder.newBuilder().setPackageName("org.example").build()
    private val packageInfo2 = PackageInfoBuilder.newBuilder().setPackageName("net.example").build()

    @Before
    fun setup() = runBlocking {
        every { mockedSettingsManager.getSafStorage() } returns settingsManager.getSafStorage()
        storage.rootBackupDir?.deleteContents(context)
            ?: error("Select a storage location in the app first!")
    }

    @After
    fun tearDown() = runBlocking {
        storage.rootBackupDir?.deleteContents(context)
        Unit
    }

    @Test
    fun testProviderPackageName() {
        assertNotNull(storagePlugin.providerPackageName)
    }

    @Test
    fun testTest() = runBlocking(Dispatchers.IO) {
        assertTrue(storagePlugin.test())
    }

    @Test
    fun testGetFreeSpace() = runBlocking(Dispatchers.IO) {
        val freeBytes = storagePlugin.getFreeSpace() ?: error("no free space retrieved")
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
        assertEquals(0, storagePlugin.getAvailableBackups()?.toList()?.size)

        // prepare returned tokens requested when initializing device
        every { mockedSettingsManager.getToken() } returnsMany listOf(token, token + 1, token + 1)

        // start new restore set and initialize device afterwards
        storagePlugin.startNewRestoreSet(token)
        storagePlugin.initializeDevice()

        // write metadata (needed for backup to be recognized)
        storagePlugin.getOutputStream(token, FILE_BACKUP_METADATA)
            .writeAndClose(getRandomByteArray())

        // one backup available now
        assertEquals(1, storagePlugin.getAvailableBackups()?.toList()?.size)

        // initializing again (with another restore set) does add a restore set
        storagePlugin.startNewRestoreSet(token + 1)
        storagePlugin.initializeDevice()
        storagePlugin.getOutputStream(token + 1, FILE_BACKUP_METADATA)
            .writeAndClose(getRandomByteArray())
        assertEquals(2, storagePlugin.getAvailableBackups()?.toList()?.size)

        // initializing again (without new restore set) doesn't change number of restore sets
        storagePlugin.initializeDevice()
        storagePlugin.getOutputStream(token + 1, FILE_BACKUP_METADATA)
            .writeAndClose(getRandomByteArray())
        assertEquals(2, storagePlugin.getAvailableBackups()?.toList()?.size)

        // ensure that the new backup dir exist
        assertTrue(storage.currentSetDir!!.exists())
    }

    @Test
    fun testMetadataWriteRead() = runBlocking(Dispatchers.IO) {
        every { mockedSettingsManager.getToken() } returns token

        storagePlugin.startNewRestoreSet(token)
        storagePlugin.initializeDevice()

        // write metadata
        val metadata = getRandomByteArray()
        storagePlugin.getOutputStream(token, FILE_BACKUP_METADATA).writeAndClose(metadata)

        // get available backups, expect only one with our token and no error
        var availableBackups = storagePlugin.getAvailableBackups()?.toList()
        check(availableBackups != null)
        assertEquals(1, availableBackups.size)
        assertEquals(token, availableBackups[0].token)

        // read metadata matches what was written earlier
        assertReadEquals(metadata, availableBackups[0].inputStreamRetriever())

        // initializing again (without changing storage) keeps restore set with same token
        storagePlugin.initializeDevice()
        storagePlugin.getOutputStream(token, FILE_BACKUP_METADATA).writeAndClose(metadata)
        availableBackups = storagePlugin.getAvailableBackups()?.toList()
        check(availableBackups != null)
        assertEquals(1, availableBackups.size)
        assertEquals(token, availableBackups[0].token)

        // metadata hasn't changed
        assertReadEquals(metadata, availableBackups[0].inputStreamRetriever())
    }

    @Test
    @Suppress("Deprecation")
    fun v0testApkWriteRead() = runBlocking {
        // initialize storage with given token
        initStorage(token)

        // write random bytes as APK
        val apk1 = getRandomByteArray(1337 * 1024)
        storagePlugin.getOutputStream(token, "${packageInfo.packageName}.apk").writeAndClose(apk1)

        // assert that read APK bytes match what was written
        assertReadEquals(
            apk1,
            legacyStoragePlugin.getApkInputStream(token, packageInfo.packageName, "")
        )

        // write random bytes as another APK
        val suffix2 = getRandomBase64(23)
        val apk2 = getRandomByteArray(23 * 1024 * 1024)

        storagePlugin.getOutputStream(token, "${packageInfo2.packageName}$suffix2.apk")
            .writeAndClose(apk2)

        // assert that read APK bytes match what was written
        assertReadEquals(
            apk2,
            legacyStoragePlugin.getApkInputStream(token, packageInfo2.packageName, suffix2)
        )
    }

    @Test
    fun testBackupRestore() = runBlocking {
        // initialize storage with given token
        initStorage(token)

        val name1 = getRandomBase64()
        val name2 = getRandomBase64()

        // no data available initially
        assertFalse(storagePlugin.hasData(token, name1))
        assertFalse(storagePlugin.hasData(token, name2))

        // write full backup data
        val data = getRandomByteArray(5 * 1024 * 1024)
        storagePlugin.getOutputStream(token, name1).writeAndClose(data)

        // data is available now, but only this token
        assertTrue(storagePlugin.hasData(token, name1))
        assertFalse(storagePlugin.hasData(token + 1, name1))

        // restore data matches backed up data
        assertReadEquals(data, storagePlugin.getInputStream(token, name1))

        // write and check data for second package
        val data2 = getRandomByteArray(5 * 1024 * 1024)
        storagePlugin.getOutputStream(token, name2).writeAndClose(data2)
        assertTrue(storagePlugin.hasData(token, name2))
        assertReadEquals(data2, storagePlugin.getInputStream(token, name2))

        // remove data of first package again and ensure that no more data is found
        storagePlugin.removeData(token, name1)
        assertFalse(storagePlugin.hasData(token, name1))

        // second package is still there
        assertTrue(storagePlugin.hasData(token, name2))

        // ensure that it gets deleted as well
        storagePlugin.removeData(token, name2)
        assertFalse(storagePlugin.hasData(token, name2))
    }

    private fun initStorage(token: Long) = runBlocking {
        every { mockedSettingsManager.getToken() } returns token
        storagePlugin.initializeDevice()
    }

}
