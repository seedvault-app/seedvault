package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.BackupNotificationManager
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.settings.Storage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

internal class BackupCoordinatorTest: BackupTest() {

    private val plugin = mockk<BackupPlugin>()
    private val kv = mockk<KVBackup>()
    private val full = mockk<FullBackup>()
    private val notificationManager = mockk<BackupNotificationManager>()

    private val backup = BackupCoordinator(context, plugin, kv, full, metadataManager, settingsManager, notificationManager)

    private val metadataOutputStream = mockk<OutputStream>()

    @Test
    fun `device initialization succeeds and delegates to plugin`() {
        every { plugin.initializeDevice() } just Runs
        every { plugin.getMetadataOutputStream() } returns metadataOutputStream
        every { metadataManager.onDeviceInitialization(metadataOutputStream) } just Runs
        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.initializeDevice())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `error notification when device initialization fails`() {
        val storage = Storage(Uri.EMPTY, getRandomString(), false)

        every { plugin.initializeDevice() } throws IOException()
        every { settingsManager.getStorage() } returns storage
        every { notificationManager.onBackupError() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        assertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `no error notification when device initialization fails on unplugged USB storage`() {
        val storage = mockk<Storage>()
        val documentFile = mockk<DocumentFile>()

        every { plugin.initializeDevice() } throws IOException()
        every { settingsManager.getStorage() } returns storage
        every { storage.isUsb } returns true
        every { storage.getDocumentFile(context) } returns documentFile
        every { documentFile.isDirectory } returns false

        assertEquals(TRANSPORT_ERROR, backup.initializeDevice())

        // finish will only be called when TRANSPORT_OK is returned, so it should throw
        every { kv.hasState() } returns false
        every { full.hasState() } returns false
        assertThrows(IllegalStateException::class.java) {
            backup.finishBackup()
        }
    }

    @Test
    fun `getBackupQuota() delegates to right plugin`() {
        val isFullBackup = Random.nextBoolean()
        val quota = Random.nextLong()

        if (isFullBackup) {
            every { full.getQuota() } returns quota
        } else {
            every { kv.getQuota() } returns quota
        }
        assertEquals(quota, backup.getBackupQuota(packageInfo.packageName, isFullBackup))
    }

    @Test
    fun `clearing KV backup data throws`() {
        every { kv.clearBackupData(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing full backup data throws`() {
        every { kv.clearBackupData(packageInfo) } just Runs
        every { full.clearBackupData(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.clearBackupData(packageInfo))
    }

    @Test
    fun `clearing backup data succeeds`() {
        every { kv.clearBackupData(packageInfo) } just Runs
        every { full.clearBackupData(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.clearBackupData(packageInfo))

        every { kv.hasState() } returns false
        every { full.hasState() } returns false

        assertEquals(TRANSPORT_OK, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to KV plugin if it has state`() {
        val result = Random.nextInt()

        every { kv.hasState() } returns true
        every { full.hasState() } returns false
        every { kv.finishBackup() } returns result

        assertEquals(result, backup.finishBackup())
    }

    @Test
    fun `finish backup delegates to full plugin if it has state`() {
        val result = Random.nextInt()

        every { kv.hasState() } returns false
        every { full.hasState() } returns true
        every { full.finishBackup() } returns result

        assertEquals(result, backup.finishBackup())
    }

}
