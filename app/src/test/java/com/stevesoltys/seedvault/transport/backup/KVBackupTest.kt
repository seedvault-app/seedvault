package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.MAX_KEY_LENGTH_SIZE
import com.stevesoltys.seedvault.header.VersionHeader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVBackupTest : BackupTest() {

    private val plugin = mockk<KVBackupPlugin>()
    private val dataInput = mockk<BackupDataInput>()
    private val notificationManager = mockk<BackupNotificationManager>()

    private val backup = KVBackup(plugin, inputFactory, headerWriter, crypto, notificationManager)

    private val key = getRandomString(MAX_KEY_LENGTH_SIZE)
    private val key64 = Base64.getEncoder().encodeToString(key.toByteArray(Utf8))
    private val value = ByteArray(23).apply { Random.nextBytes(this) }
    private val versionHeader = VersionHeader(packageName = packageInfo.packageName, key = key)

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `simple backup with one record`() = runBlocking {
        singleRecordBackup()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `@pm@ backup shows notification`() = runBlocking {
        // init plugin and give back two keys
        initPlugin(true, pmPackageInfo)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returnsMany listOf(true, true, false)
        every { dataInput.key } returnsMany listOf("key1", "key2")
        // we don't care about values, so just use the same one always
        every { dataInput.dataSize } returns value.size
        every { dataInput.readEntityData(any(), 0, value.size) } returns value.size

        // store first record and show notification for it
        every { notificationManager.onPmKvBackup("key1", 1, 2) } just Runs
        coEvery { plugin.getOutputStreamForRecord(pmPackageInfo, "a2V5MQ") } returns outputStream
        val versionHeader1 = VersionHeader(packageName = pmPackageInfo.packageName, key = "key1")
        every { headerWriter.writeVersion(outputStream, versionHeader1) } just Runs
        every { crypto.encryptHeader(outputStream, versionHeader1) } just Runs

        // store second record and show notification for it
        every { notificationManager.onPmKvBackup("key2", 2, 2) } just Runs
        coEvery { plugin.getOutputStreamForRecord(pmPackageInfo, "a2V5Mg") } returns outputStream
        val versionHeader2 = VersionHeader(packageName = pmPackageInfo.packageName, key = "key2")
        every { headerWriter.writeVersion(outputStream, versionHeader2) } just Runs
        every { crypto.encryptHeader(outputStream, versionHeader2) } just Runs

        // encrypt to and close output stream
        every { crypto.encryptMultipleSegments(outputStream, any()) } just Runs
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(pmPackageInfo, data, 0))
        assertTrue(backup.hasState())

        every { plugin.packageFinished(pmPackageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())

        // verify that notifications were shown
        verifyOrder {
            notificationManager.onPmKvBackup("key1", 1, 2)
            notificationManager.onPmKvBackup("key2", 2, 2)
        }
    }

    @Test
    fun `incremental backup with no data gets rejected`() = runBlocking {
        coEvery { plugin.hasDataForPackage(packageInfo) } returns false
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(
            TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
            backup.performBackup(packageInfo, data, FLAG_INCREMENTAL)
        )
        assertFalse(backup.hasState())
    }

    @Test
    fun `check for existing data throws exception`() = runBlocking {
        coEvery { plugin.hasDataForPackage(packageInfo) } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `non-incremental backup with data clears old data first`() = runBlocking {
        singleRecordBackup(true)
        coEvery { plugin.removeDataOfPackage(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `ignoring exception when clearing data when non-incremental backup has data`() =
        runBlocking {
            singleRecordBackup(true)
            coEvery { plugin.removeDataOfPackage(packageInfo) } throws IOException()

            assertEquals(
                TRANSPORT_OK,
                backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL)
            )
            assertTrue(backup.hasState())
            assertEquals(TRANSPORT_OK, backup.finishBackup())
            assertFalse(backup.hasState())
        }

    @Test
    fun `exception while reading next header`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading value`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns value.size
        every { dataInput.readEntityData(any(), 0, value.size) } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `no data records`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(false))
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while writing version header`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        coEvery { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } throws IOException()
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `exception while writing encrypted value to output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        writeHeaderAndEncrypt()
        coEvery { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } just Runs
        every { crypto.encryptMultipleSegments(outputStream, any()) } throws IOException()
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `exception while flushing output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        writeHeaderAndEncrypt()
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } throws IOException()
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `ignoring exception while closing output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true, false))
        writeHeaderAndEncrypt()
        every { outputStream.write(value) } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

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
        every { plugin.packageFinished(packageInfo) } just Runs
    }

    private fun initPlugin(hasDataForPackage: Boolean = false, pi: PackageInfo = packageInfo) {
        coEvery { plugin.hasDataForPackage(pi) } returns hasDataForPackage
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
        coEvery { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { headerWriter.writeVersion(outputStream, versionHeader) } just Runs
        every { crypto.encryptHeader(outputStream, versionHeader) } just Runs
        every { crypto.encryptMultipleSegments(outputStream, any()) } just Runs
    }

}
