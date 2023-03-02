/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts

import android.Manifest.permission.READ_CONTACTS
import android.Manifest.permission.WRITE_CONTACTS
import android.app.backup.BackupAgent
import android.app.backup.BackupAgent.TYPE_FILE
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import org.calyxos.backup.contacts.ContactsBackupAgent.BACKUP_FILE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Optional
import kotlin.random.Random

/**
 * Limited unit tests as the code itself is small and not very testable.
 */
class ContactsBackupAgentTest {

    @get:Rule
    var folder = TemporaryFolder()

    private val context: Context = mockk()
    private val fileHandler: FullBackupFileHandler = mockk()
    private val agent = ContactsBackupAgent(context, fileHandler)

    private val data: FullBackupDataOutput = mockk()

    @Test
    fun `backup is skipped when not encrypted or device-to-device`() {
        every { data.transportFlags } returns 0
        agent.onFullBackup(data)
    }

    @Test
    fun `missing read contacts permission throws`() {
        expectEncryptedOrDevice2DeviceTransport()
        every { context.checkSelfPermission(READ_CONTACTS) } returns PERMISSION_DENIED

        try {
            agent.onFullBackup(data)
            fail("IOException was not thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("READ_CONTACTS"))
        }
    }

    @Test
    fun `no contacts does not throw or attempt backup`() {
        expectEncryptedOrDevice2DeviceTransport()
        every { context.checkSelfPermission(READ_CONTACTS) } returns PERMISSION_GRANTED
        mockkConstructor(VCardExporter::class)
        every { anyConstructed<VCardExporter>().vCardInputStream } returns Optional.empty()

        agent.onFullBackup(data)
    }

    @Test
    fun `backup works`() {
        val backupBytes = Random.nextBytes(42)
        val inputStream = ByteArrayInputStream(backupBytes)
        val filesDir = folder.newFolder()

        expectEncryptedOrDevice2DeviceTransport()
        every { context.checkSelfPermission(READ_CONTACTS) } returns PERMISSION_GRANTED
        every { context.filesDir } returns filesDir
        mockkConstructor(VCardExporter::class)
        every { anyConstructed<VCardExporter>().vCardInputStream } returns Optional.of(inputStream)
        every { fileHandler.fullBackupFile(any(), data) } just Runs

        agent.onFullBackup(data)
    }

    private fun expectEncryptedOrDevice2DeviceTransport() {
        every { data.transportFlags } returns listOf(
            BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED,
            BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER
        ).random() // both flags should allow the backup
    }

    @Test
    fun `missing write contacts permission throws`() {
        every { context.checkSelfPermission(WRITE_CONTACTS) } returns PERMISSION_DENIED

        try {
            agent.onRestoreFile(null, 0, null, 0, 0, 0)
            fail("IOException was not thrown")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("WRITE_CONTACTS"))
        }
    }

    @Test
    fun `restore works`() {
        val filesDir = folder.newFolder()
        val file = File(filesDir, BACKUP_FILE)
        val restoreBytes = Random.nextBytes(42)
        file.writeBytes(restoreBytes)

        every { context.checkSelfPermission(WRITE_CONTACTS) } returns PERMISSION_GRANTED
        every { context.filesDir } returns filesDir

        agent.onRestoreFile(null, file.length(), null, TYPE_FILE, 0, 0)

        assertFalse(file.exists())
    }

}
