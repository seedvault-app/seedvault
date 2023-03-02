/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import android.app.backup.BackupTransport.TRANSPORT_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForFull
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.FileInputStream
import java.io.IOException
import kotlin.random.Random

internal class FullBackupTest : BackupTest() {

    private val storagePluginManager: StoragePluginManager = mockk()
    private val plugin = mockk<StoragePlugin<*>>()
    private val notificationManager = mockk<BackupNotificationManager>()
    private val backup = FullBackup(
        pluginManager = storagePluginManager,
        settingsManager = settingsManager,
        nm = notificationManager,
        inputFactory = inputFactory,
        crypto = crypto,
    )

    private val bytes = ByteArray(23).apply { Random.nextBytes(this) }
    private val inputStream = mockk<FileInputStream>()
    private val ad = getADForFull(VERSION, packageInfo.packageName)

    init {
        every { storagePluginManager.appPlugin } returns plugin
    }

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `checkFullBackupSize exceeds quota`() {
        every { settingsManager.isQuotaUnlimited() } returns false

        assertEquals(
            TRANSPORT_QUOTA_EXCEEDED,
            backup.checkFullBackupSize(DEFAULT_QUOTA_FULL_BACKUP + 1)
        )
    }

    @Test
    fun `checkFullBackupSize does not exceed quota when unlimited`() {
        every { settingsManager.isQuotaUnlimited() } returns true

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(quota + 1))
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
        every { settingsManager.isQuotaUnlimited() } returns false

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(1))
    }

    @Test
    fun `checkFullBackupSize accepts max data`() {
        every { settingsManager.isQuotaUnlimited() } returns false

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(quota))
    }

    @Test
    fun `performFullBackup runs ok`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData first call over quota`() = runBlocking {
        every { settingsManager.isQuotaUnlimited() } returns false
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        val numBytes = (quota + 1).toInt()
        expectSendData(numBytes)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.sendBackupData(numBytes))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData second call over quota`() = runBlocking {
        every { settingsManager.isQuotaUnlimited() } returns false
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        val numBytes1 = quota.toInt()
        expectSendData(numBytes1)
        val numBytes2 = 1
        expectSendData(numBytes2)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes1))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.sendBackupData(numBytes2))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when reading from InputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        every { settingsManager.isQuotaUnlimited() } returns false
        every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
        every { inputStream.read(any(), any(), bytes.size) } throws IOException()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when getting outputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        every { settingsManager.isQuotaUnlimited() } returns false
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.getOutputStream(token, name) } throws IOException()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when writing header`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream

        every { settingsManager.isQuotaUnlimited() } returns false
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.getOutputStream(token, name) } returns outputStream
        every { inputFactory.getInputStream(data) } returns inputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } throws IOException()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when writing encrypted data to OutputStream`() =
        runBlocking {
            every { inputFactory.getInputStream(data) } returns inputStream
            expectInitializeOutputStream()
            every { settingsManager.isQuotaUnlimited() } returns false
            every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
            every { inputStream.read(any(), any(), bytes.size) } returns bytes.size
            every { encryptedOutputStream.write(any<ByteArray>()) } throws IOException()
            expectClearState()

            assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
            assertTrue(backup.hasState())
            assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
            assertTrue(backup.hasState())
            assertEquals(TRANSPORT_OK, backup.finishBackup())
            assertFalse(backup.hasState())
        }

    @Test
    fun `sendBackupData runs ok`() = runBlocking {
        every { settingsManager.isQuotaUnlimited() } returns false
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        val numBytes1 = (quota / 2).toInt()
        expectSendData(numBytes1)
        val numBytes2 = (quota / 2).toInt()
        expectSendData(numBytes2)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes1))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes2))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearBackupData delegates to plugin`() = runBlocking {
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.removeData(token, name) } just Runs

        backup.clearBackupData(packageInfo, token, salt)
    }

    @Test
    fun `cancel full backup runs ok`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        expectClearState()
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.removeData(token, name) } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        backup.cancelFullBackup(token, salt, false)
        assertFalse(backup.hasState())
    }

    @Test
    fun `cancel full backup ignores exception when calling plugin`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        expectClearState()
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.removeData(token, name) } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        backup.cancelFullBackup(token, salt, false)
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState throws exception when flushing OutputStream`() = runBlocking {
        every { settingsManager.isQuotaUnlimited() } returns false
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        val numBytes = 42
        expectSendData(numBytes)
        every { encryptedOutputStream.flush() } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes))
        assertEquals(TRANSPORT_ERROR, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing OutputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } throws IOException()
        every { inputStream.close() } just Runs
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing InputStream`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } throws IOException()
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing ParcelFileDescriptor`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns inputStream
        expectInitializeOutputStream()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    private fun expectInitializeOutputStream() {
        every { crypto.getNameForPackage(salt, packageInfo.packageName) } returns name
        coEvery { plugin.getOutputStream(token, name) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } just Runs
    }

    private fun expectSendData(numBytes: Int, readBytes: Int = numBytes) {
        every { inputStream.read(any(), any(), numBytes) } returns readBytes
        every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
        every { encryptedOutputStream.write(any<ByteArray>()) } just Runs
    }

    private fun expectClearState() {
        every { encryptedOutputStream.flush() } just Runs
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } just Runs
    }

}
