package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_DATA_NOT_CHANGED
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.MAX_KEY_LENGTH_SIZE
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.plugins.StoragePlugin
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVBackupTest : BackupTest() {

    private val plugin = mockk<StoragePlugin>()
    private val dataInput = mockk<BackupDataInput>()
    private val dbManager = mockk<KvDbManager>()

    private val backup = KVBackup(plugin, settingsManager, inputFactory, crypto, dbManager)

    private val db = mockk<KVDb>()
    private val packageName = packageInfo.packageName
    private val key = getRandomString(MAX_KEY_LENGTH_SIZE)
    private val dataValue = Random.nextBytes(23)
    private val dbBytes = Random.nextBytes(42)
    private val inputStream = ByteArrayInputStream(dbBytes)

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `simple backup with one record`() = runBlocking {
        singleRecordBackup()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(packageInfo, backup.getCurrentPackage())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `incremental backup with no data gets rejected`() = runBlocking {
        initPlugin(false)
        every { db.close() } just Runs

        assertEquals(
            TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
            backup.performBackup(packageInfo, data, FLAG_INCREMENTAL, token, salt)
        )
        assertFalse(backup.hasState())
    }

    @Test
    fun `non-incremental backup with data clears old data first`() = runBlocking {
        singleRecordBackup(true)
        coEvery { plugin.removeData(token, name) } just Runs
        every { dbManager.deleteDb(packageName) } returns true

        assertEquals(
            TRANSPORT_OK,
            backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL, token, salt)
        )
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `ignoring exception when clearing data when non-incremental backup has data`() =
        runBlocking {
            singleRecordBackup(true)
            coEvery { plugin.removeData(token, name) } throws IOException()

            assertEquals(
                TRANSPORT_OK,
                backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL, token, salt)
            )
            assertTrue(backup.hasState())
            assertEquals(TRANSPORT_OK, backup.finishBackup())
            assertFalse(backup.hasState())
        }

    @Test
    fun `package with no new data comes back ok right away`() = runBlocking {
        every { crypto.getNameForPackage(salt, packageName) } returns name
        every { dbManager.getDb(packageName) } returns db
        every { data.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performBackup(packageInfo, data, FLAG_DATA_NOT_CHANGED, token, salt)
        )
        assertTrue(backup.hasState())

        verify { data.close() }
        every { db.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading next header`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } throws IOException()
        every { db.close() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0, token, salt))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading value`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns dataValue.size
        every { dataInput.readEntityData(any(), 0, dataValue.size) } throws IOException()
        every { db.close() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0, token, salt))
        assertFalse(backup.hasState())
    }

    @Test
    fun `no data records`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(false))

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())

        every { db.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `null data deletes key`() = runBlocking {
        initPlugin(true)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true andThen false
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns -1 // just documented by example code in LocalTransport
        every { db.delete(key) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())

        uploadData()

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while writing version`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true, false))
        every { db.put(key, dataValue) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())

        every { db.vacuum() } just Runs
        every { db.close() } just Runs
        coEvery { plugin.getOutputStream(token, name) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } throws IOException()
        every { outputStream.close() } just Runs
        assertEquals(TRANSPORT_ERROR, backup.finishBackup())
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `exception while writing encrypted value to output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true, false))
        every { db.put(key, dataValue) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())

        every { db.vacuum() } just Runs
        every { db.close() } just Runs
        coEvery { plugin.getOutputStream(token, name) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } just Runs
        val ad = getADForKV(VERSION, packageInfo.packageName)
        every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
        every { encryptedOutputStream.write(any<ByteArray>()) } throws IOException()

        assertEquals(TRANSPORT_ERROR, backup.finishBackup())
        assertFalse(backup.hasState())

        verify {
            encryptedOutputStream.close()
            outputStream.close()
        }
    }

    @Test
    fun `no upload when we back up @pm@ while we can't do backups`() = runBlocking {
        every { dbManager.existsDb(pmPackageInfo.packageName) } returns false
        every { crypto.getNameForPackage(salt, pmPackageInfo.packageName) } returns name
        every { dbManager.getDb(pmPackageInfo.packageName) } returns db
        every { settingsManager.canDoBackupNow() } returns false
        every { db.put(key, dataValue) } just Runs
        getDataInput(listOf(true, false))

        assertEquals(TRANSPORT_OK, backup.performBackup(pmPackageInfo, data, 0, token, salt))
        assertTrue(backup.hasState())
        assertEquals(pmPackageInfo, backup.getCurrentPackage())

        every { db.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())

        coVerify(exactly = 0) {
            plugin.getOutputStream(token, name)
        }
    }

    private fun singleRecordBackup(hasDataForPackage: Boolean = false) {
        initPlugin(hasDataForPackage)
        every { db.put(key, dataValue) } just Runs
        getDataInput(listOf(true, false))
        uploadData()
    }

    private fun initPlugin(hasDataForPackage: Boolean = false, pi: PackageInfo = packageInfo) {
        every { dbManager.existsDb(pi.packageName) } returns hasDataForPackage
        every { crypto.getNameForPackage(salt, pi.packageName) } returns name
        every { dbManager.getDb(pi.packageName) } returns db
    }

    private fun createBackupDataInput() {
        every { inputFactory.getBackupDataInput(data) } returns dataInput
    }

    private fun getDataInput(returnValues: List<Boolean>) {
        createBackupDataInput()
        every { dataInput.readNextHeader() } returnsMany returnValues
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns dataValue.size
        val slot = CapturingSlot<ByteArray>()
        every { dataInput.readEntityData(capture(slot), 0, dataValue.size) } answers {
            dataValue.copyInto(slot.captured)
            dataValue.size
        }
    }

    private fun uploadData() {
        every { db.vacuum() } just Runs
        every { db.close() } just Runs

        coEvery { plugin.getOutputStream(token, name) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } just Runs
        val ad = getADForKV(VERSION, packageInfo.packageName)
        every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
        every { encryptedOutputStream.write(any<ByteArray>()) } just Runs // gzip header
        every { encryptedOutputStream.write(any(), any(), any()) } just Runs // stream copy
        every { dbManager.getDbInputStream(packageName) } returns inputStream
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
    }

}
