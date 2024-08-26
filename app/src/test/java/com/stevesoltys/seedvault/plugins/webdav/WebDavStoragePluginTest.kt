/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.transport.TransportTest
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34], // TODO: Drop once robolectric supports 35
    application = TestApp::class
)
internal class WebDavStoragePluginTest : TransportTest() {

    private val plugin = WebDavStoragePlugin(WebDavTestConfig.getConfig())

    @Test
    fun `test self-test`() = runBlocking {
        assertTrue(plugin.test())

        val plugin2 = WebDavStoragePlugin(WebDavConfig("https://github.com/", "", ""))
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
        } finally {
            // remove data at the end, so consecutive test runs pass
            plugin.removeData(token, FILE_BACKUP_METADATA)
        }
    }

}
