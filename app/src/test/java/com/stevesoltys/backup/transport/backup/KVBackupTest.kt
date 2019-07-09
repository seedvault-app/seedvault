package com.stevesoltys.backup.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.*
import com.stevesoltys.backup.getRandomString
import com.stevesoltys.backup.header.MAX_KEY_LENGTH_SIZE
import com.stevesoltys.backup.header.Utf8
import com.stevesoltys.backup.header.VersionHeader
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.*
import kotlin.random.Random

internal class KVBackupTest : BackupTest() {

    private val plugin = mockk<KVBackupPlugin>()
    private val dataInput = mockk<BackupDataInput>()

    private val backup = KVBackup(plugin, inputFactory, headerWriter, crypto)

    private val key = getRandomString(MAX_KEY_LENGTH_SIZE)
    private val key64 = Base64.getEncoder().encodeToString(key.toByteArray(Utf8))
    private val value = ByteArray(23).apply { Random.nextBytes(this) }
    private val versionHeader = VersionHeader(packageName = packageInfo.packageName, key = key)

    @Test
    fun `now is a good time for a backup`() {
        assertEquals(0, backup.requestBackupTime())
    }

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `simple backup with one record`() {
        singleRecordBackup()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `incremental backup with no data gets rejected`() {
        every { plugin.hasDataForPackage(packageInfo) } returns false

        assertEquals(TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED, backup.performBackup(packageInfo, data, FLAG_INCREMENTAL))
        assertFalse(backup.hasState())
    }

    @Test
    fun `check for existing data throws exception`() {
        every { plugin.hasDataForPackage(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `non-incremental backup with data clears old data first`() {
        singleRecordBackup(true)
        every { plugin.removeDataOfPackage(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `ignoring exception when clearing data when non-incremental backup has data`() {
        singleRecordBackup(true)
        every { plugin.removeDataOfPackage(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `ensuring storage throws exception`() {
        every { plugin.hasDataForPackage(packageInfo) } returns false
        every { plugin.ensureRecordStorageForPackage(packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading next header`() {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading value`() {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns value.size
        every { dataInput.readEntityData(any(), 0, value.size) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `no data records`() {
        initPlugin(false)
        getDataInput(listOf(false))

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while writing version header`() {
        initPlugin(false)
        getDataInput(listOf(true))
        every { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while writing encrypted value to output stream`() {
        initPlugin(false)
        getDataInput(listOf(true))
        writeHeaderAndEncrypt()
        every { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } just Runs
        every { crypto.encryptSegment(outputStream, any()) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while flushing output stream`() {
        initPlugin(false)
        getDataInput(listOf(true))
        writeHeaderAndEncrypt()
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `ignoring exception while closing output stream`() {
        initPlugin(false)
        getDataInput(listOf(true, false))
        writeHeaderAndEncrypt()
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } throws IOException()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    private fun singleRecordBackup(hasDataForPackage: Boolean = false) {
        initPlugin(hasDataForPackage)
        getDataInput(listOf(true, false))
        writeHeaderAndEncrypt()
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs
    }

    private fun initPlugin(hasDataForPackage: Boolean = false) {
        every { plugin.hasDataForPackage(packageInfo) } returns hasDataForPackage
        every { plugin.ensureRecordStorageForPackage(packageInfo) } just Runs
    }

    private fun createBackupDataInput() {
        every { inputFactory.getBackupDataInput(data) } returns dataInput
    }

    private fun getDataInput(returnValues: List<Boolean>) {
        createBackupDataInput()
        every { dataInput.readNextHeader() } returnsMany returnValues
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns value.size
        every { dataInput.readEntityData(any(), 0, value.size) } returns value.size
    }

    private fun writeHeaderAndEncrypt() {
        every { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } just Runs
        every { crypto.encryptHeader(outputStream, versionHeader) } just Runs
        every { crypto.encryptSegment(outputStream, any()) } just Runs
    }

}