package com.stevesoltys.backup.transport.backup

import android.app.backup.BackupTransport.*
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.FileInputStream
import java.io.IOException
import kotlin.random.Random

internal class FullBackupTest : BackupTest() {

    private val plugin = mockk<FullBackupPlugin>()
    private val backup = FullBackup(plugin, inputFactory, headerWriter, crypto)

    private val bytes = ByteArray(23).apply { Random.nextBytes(this) }
    private val closeBytes = ByteArray(42).apply { Random.nextBytes(this) }
    private val inputStream = mockk<FileInputStream>()

    @Test
    fun `now is a good time for a backup`() {
        assertEquals(0, backup.requestFullBackupTime())
    }

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `checkFullBackupSize exceeds quota`() {
        every { plugin.getQuota() } returns quota

        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.checkFullBackupSize(quota + 1))
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
        every { plugin.getQuota() } returns quota

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(1))
    }

    @Test
    fun `checkFullBackupSize accepts max data`() {
        every { plugin.getQuota() } returns quota

        assertEquals(TRANSPORT_OK, backup.checkFullBackupSize(quota))
    }

    @Test
    fun `performFullBackup throws exception when getting outputStream`() {
        every { plugin.getOutputStream(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performFullBackup(packageInfo, data))
        assertFalse(backup.hasState())
    }

    @Test
    fun `performFullBackup throws exception when writing header`() {
        every { plugin.getOutputStream(packageInfo) } returns outputStream
        every { inputFactory.getInputStream(data) } returns inputStream
        every { headerWriter.writeVersion(outputStream, header) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performFullBackup(packageInfo, data))
        assertFalse(backup.hasState())
    }

    @Test
    fun `performFullBackup runs ok`() {
        expectPerformFullBackup()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData first call over quota`() {
        expectPerformFullBackup()
        val numBytes = (quota + 1).toInt()
        expectSendData(numBytes)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.sendBackupData(numBytes))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData second call over quota`() {
        expectPerformFullBackup()
        val numBytes1 = quota.toInt()
        expectSendData(numBytes1)
        val numBytes2 = 1
        expectSendData(numBytes2)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes1))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_QUOTA_EXCEEDED, backup.sendBackupData(numBytes2))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when reading from InputStream`() {
        expectPerformFullBackup()
        every { plugin.getQuota() } returns quota
        every { inputStream.read(any(), any(), bytes.size) } throws IOException()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData throws exception when writing encrypted data to OutputStream`() {
        expectPerformFullBackup()
        every { plugin.getQuota() } returns quota
        every { inputStream.read(any(), any(), bytes.size) } returns bytes.size
        every { crypto.encryptSegment(outputStream, any()) } throws IOException()
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.sendBackupData(bytes.size))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `sendBackupData runs ok`() {
        expectPerformFullBackup()
        val numBytes1 = (quota / 2).toInt()
        expectSendData(numBytes1)
        val numBytes2 = (quota / 2).toInt()
        expectSendData(numBytes2)
        expectClearState()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes1))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.sendBackupData(numBytes2))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearBackupData delegates to plugin`() {
        every { plugin.removeDataOfPackage(packageInfo) } just Runs

        backup.clearBackupData(packageInfo)
    }

    @Test
    fun `cancel full backup runs ok`() {
        expectPerformFullBackup()
        expectClearState()
        every { plugin.removeDataOfPackage(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        backup.cancelFullBackup()
        assertFalse(backup.hasState())
    }

    @Test
    fun `cancel full backup ignores exception when calling plugin`() {
        expectPerformFullBackup()
        expectClearState()
        every { plugin.removeDataOfPackage(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        backup.cancelFullBackup()
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState throws exception when flushing OutputStream`() {
        expectPerformFullBackup()
        every { outputStream.write(closeBytes) } just Runs
        every { outputStream.flush() } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_ERROR, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing OutputStream`() {
        expectPerformFullBackup()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } throws IOException()
        every { inputStream.close() } just Runs
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing InputStream`() {
        expectPerformFullBackup()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } throws IOException()
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `clearState ignores exception when closing ParcelFileDescriptor`() {
        expectPerformFullBackup()
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performFullBackup(packageInfo, data))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    private fun expectPerformFullBackup() {
        every { plugin.getOutputStream(packageInfo) } returns outputStream
        every { inputFactory.getInputStream(data) } returns inputStream
        every { headerWriter.writeVersion(outputStream, header) } just Runs
        every { crypto.encryptHeader(outputStream, header) } just Runs
    }

    private fun expectSendData(numBytes: Int, readBytes: Int = numBytes) {
        every { plugin.getQuota() } returns quota
        every { inputStream.read(any(), any(), numBytes) } returns readBytes
        every { crypto.encryptSegment(outputStream, any()) } just Runs
    }

    private fun expectClearState() {
        every { outputStream.write(closeBytes) } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
        every { inputStream.close() } just Runs
        every { data.close() } just Runs
    }

}
