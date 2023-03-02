/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts

import android.app.backup.BackupAgent
import android.app.backup.BackupAgent.TYPE_FILE
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import org.calyxos.backup.contacts.ContactUtils.Contact
import org.calyxos.backup.contacts.ContactsBackupAgent.BACKUP_FILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val utils = ContactUtils(resolver)

    private val fileHandler = object : FullBackupFileHandler {
        var bytes: ByteArray? = null
        override fun fullBackupFile(file: File, output: FullBackupDataOutput?) {
            bytes = file.readBytes()
        }
    }

    // we are calling agent ourselves, because using bmgr will kill our process making test fail
    private val agent = ContactsBackupAgent(context, fileHandler)

    @Test
    fun testBackupAndRestore() {
        assertEquals(
            "Test will remove *all* contacts and thus requires empty address book",
            0,
            utils.getNumberOfContacts()
        )
        val contacts = listOf(
            Contact("Test Contact 1", "+49123456789", "test@example.com"),
            Contact("Test Contact 2", "+559876543210", "test@example.org")
        )
        for (c in contacts) utils.addContact(c)
        assertEquals(2, utils.getNumberOfContacts())
        assertNull(fileHandler.bytes)

        val data: FullBackupDataOutput = mockk()
        every { data.transportFlags } returns BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED

        // do actual backup by calling agent directly
        agent.onFullBackup(data)
        assertTrue(fileHandler.bytes!!.isNotEmpty())

        // preparing file for restore
        val tmp = File(context.cacheDir, "tmp")
        tmp.writeBytes(fileHandler.bytes!!)
        val fd = ParcelFileDescriptor.open(tmp, MODE_READ_ONLY)
        val dest = File(context.filesDir, BACKUP_FILE)

        // now delete all contacts, so we can restore them
        utils.deleteAllContacts()
        assertEquals(0, utils.getNumberOfContacts())

        // do restore by calling agent directly
        val mode = 384L // 0600 in octal
        agent.onRestoreFile(fd, tmp.length(), dest, TYPE_FILE, mode, 0)

        // check that restored contacts match what we backed up
        assertEquals(2, utils.getNumberOfContacts())
        assertEquals(contacts.sortedBy { it.name }, utils.getContacts().sortedBy { it.name })

        // delete everything again
        utils.deleteAllContacts()
        assertEquals(0, utils.getNumberOfContacts())
    }

}
