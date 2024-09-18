/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.FileInputStream
import java.io.IOException
import kotlin.random.Random

internal class FullBackupTest : BackupTest() {

    private val backupReceiver = mockk<BackupReceiver>()
    private val notificationManager = mockk<BackupNotificationManager>()
    private val backup = FullBackup(
        settingsManager = settingsManager,
        nm = notificationManager,
        backupReceiver = backupReceiver,
        inputFactory = inputFactory,
    )

    private val bytes = ByteArray(23).apply { Random.nextBytes(this) }
    private val inputStream = mockk<FileInputStream>()
    private val backupData = apkBackupData

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState)
    }

    @Test
    fun `checkFullBackupSize exceeds quota`() {
        every { settingsManager.quota } returns quota

        assertEquals(
            TRANSPORT_QUOTA_EXCEEDED,
            backup.checkFullBackupSize(quota + 1)
        )
    }

    @Test
    fun `checkFullBackupSize for no data`() {
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(0))
    }

    @Test
    fun `checkFullBackupSize for negative data`() {
        assertEquals(TRANSPORT_PACKAGE_REJECTED, backup.checkFullBackupSize(-1))
    }

    @Test
    fun `checkFullBackupSize accepts min data`() {
        every { settingsManager.quota } returns quota

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(1))
    }

    @Test
    fun `checkFullBackupSize accepts max data`() {
        every { settingsManager.quota } returns quota

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(quota))
    }

    @Test
    fun `performFullBackup runs ok`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `sendBackupData first call over quota`() = runBlocking {
        val quota = Random.nextInt(1, Int.MAX_VALUE).toLong()
        every { settingsManager.quota } returns quota
        every { inputFactory.getInputStream(data) } returns inputStream
        val numBytes = (quota + 1).toInt()
        expectSendData(numBytes)

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.sendBackupData(numBytes))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `sendBackupData subsequent calls over quota`() = runBlocking {
        val quota = (50 * 1024 * 1024).toLong()
        every { settingsManager.quota } returns quota
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        // split up sending data in smaller chunks, so we don't run out of heap space
        var sendResult: Int = TRANSPORT_ERROR
        val numBytes = (quota / 1024).toInt()
        for (i in 0..1024) {
            expectSendData(numBytes)
            sendResult = backup.sendBackupData(numBytes)
            assertTrue(backup.hasState)
            if (sendResult == TRANSPORT_QUOTA_EXCEEDED) break
        }
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, sendResult)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        // in reality, this may not call finishBackup(), but cancelBackup()
        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `sendBackupData throws exception when reading from InputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        every { settingsManager.quota } returns quota
        every { inputStream.read(any(), any(), bytes.size) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `sendBackupData throws exception when sending data`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        every { settingsManager.quota } returns quota
        every { inputStream.read(any(), 0, bytes.size) } returns bytes.size
        coEvery { backupReceiver.addBytes("FullBackup $packageName", any()) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `sendBackupData throws exception when finalizing`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        every { settingsManager.quota } returns quota
        expectSendData(bytes.size)

        assertEquals(TRANSPORT_OK, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } throws IOException()
        expectClearState()

        assertThrows<IOException> {
            backup.finishBackup()
        }
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `sendBackupData runs ok`() = runBlocking {
        every { settingsManager.quota } returns quota
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        val numBytes1 = 2342
        expectSendData(numBytes1)
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes1))
        assertTrue(backup.hasState)

        val numBytes2 = 4223
        expectSendData(numBytes2)
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes2))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `cancel full backup runs ok`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        expectClearState()

        backup.cancelFullBackup()
        assertFalse(backup.hasState)
    }

    @Test
    fun `cancel full backup throws exception when finalizing`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } throws IOException()
        expectClearState()

        backup.cancelFullBackup()
        assertFalse(backup.hasState)
    }

    @Test
    fun `clearState ignores exception when closing InputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } throws IOException()
        every { data.close() } just Runs

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    @Test
    fun `clearState ignores exception when closing ParcelFileDescriptor`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        coEvery { backupReceiver.finalize("FullBackup $packageName") } returns backupData
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } throws IOException()

        assertEquals(backupData, backup.finishBackup())
        assertFalse(backup.hasState)
    }

    private fun expectSendData(numBytes: Int, readBytes: Int = numBytes) {
        every { inputStream.read(any(), any(), numBytes) } returns readBytes
        coEvery { backupReceiver.addBytes("FullBackup $packageName", any()) } just Runs
    }

    private fun expectClearState() {
        every { encryptedOutputStream.flush() } just Runs
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } just Runs
    }

}
