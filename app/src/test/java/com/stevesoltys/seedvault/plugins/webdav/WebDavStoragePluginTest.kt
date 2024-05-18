/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.transport.TransportTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [33], // robolectric does not support 34, yet
    application = TestApp::class
)
internal class WebDavStoragePluginTest : TransportTest() {

    private val plugin = WebDavStoragePlugin(context, WebDavTestConfig.getConfig())

    @Test
    fun `test self-test`() = runBlocking {
        assertTrue(plugin.test())

        val plugin2 = WebDavStoragePlugin(context, WebDavConfig("https://github.com/", "", ""))
        val e = assertThrows<Exception> {
            assertFalse(plugin2.test())
        }
        println(e)
    }

    @Test
    fun `test getting free space`() = runBlocking {
        val freeBytes = plugin.getFreeSpace() ?: fail()
        assertTrue(freeBytes > 0)
    }

    @Test
    fun `test restore sets and reading+writing`() = runBlocking {
        val token = System.currentTimeMillis()
        val metadata = getRandomByteArray()

        // need to initialize, to have root .SeedVaultAndroidBackup folder
        plugin.initializeDevice()
        plugin.startNewRestoreSet(token)

        // initially, we don't have any backups
        assertEquals(emptySet<EncryptedMetadata>(), plugin.getAvailableBackups()?.toSet())

        // and no data
        assertFalse(plugin.hasData(token, FILE_BACKUP_METADATA))

        // write out the metadata file
        plugin.getOutputStream(token, FILE_BACKUP_METADATA).use {
            it.write(metadata)
        }
        try {
            // now we have one backup matching our token
            val backups = plugin.getAvailableBackups()?.toSet() ?: fail()
            assertEquals(1, backups.size)
            assertEquals(token, backups.first().token)

            // read back written data
            assertArrayEquals(
                metadata,
                plugin.getInputStream(token, FILE_BACKUP_METADATA).use { it.readAllBytes() },
            )

            // it has data now
            assertTrue(plugin.hasData(token, FILE_BACKUP_METADATA))
        } finally {
            // remove data at the end, so consecutive test runs pass
            plugin.removeData(token, FILE_BACKUP_METADATA)
        }
    }

    @Test
    fun `test streams for non-existent data`() = runBlocking {
        val token = Random.nextLong(System.currentTimeMillis(), 9999999999999)
        val file = getRandomString()

        assertFalse(plugin.hasData(token, file))

        assertThrows<IOException> {
            plugin.getOutputStream(token, file).use { it.write(getRandomByteArray()) }
        }

        assertThrows<IOException> {
            plugin.getInputStream(token, file).use {
                it.readAllBytes()
            }
        }
        Unit
    }

    @Test
    fun `test missing root dir`() = runBlocking {
        val plugin = WebDavStoragePlugin(context, WebDavTestConfig.getConfig(), getRandomString())

        assertNull(plugin.getAvailableBackups())

        assertFalse(plugin.hasData(42L, "foo"))

        assertThrows<IOException> {
            plugin.removeData(42L, "foo")
        }
        Unit
    }

}
