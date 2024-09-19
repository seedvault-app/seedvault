/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_DATA_NOT_CHANGED
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.repo.BackupReceiver
import io.mockk.CapturingSlot
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
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random

internal class KVBackupTest : BackupTest() {

    private val backupReceiver = mockk<BackupReceiver>()
    private val dataInput = mockk<BackupDataInput>()
    private val dbManager = mockk<KvDbManager>()

    private val backup = KVBackup(
        backupReceiver = backupReceiver,
        inputFactory = inputFactory,
        dbManager = dbManager,
    )

    private val db = mockk<KVDb>()
    private val dataValue = Random.nextBytes(23)
    private val dbBytes = Random.nextBytes(42)
    private val inputStream = ByteArrayInputStream(dbBytes)

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState)
    }

    @Test
    fun `simple backup with one record`() = runBlocking {
        singleRecordBackup()

        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)
        assertEquals(packageInfo, backup.currentPackageInfo)

        assertEquals(apkBackupData, backup.finishBackup())
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `incremental backup with no data gets rejected`() = runBlocking {
        initPlugin(false)
        every { data.close() } just Runs
        every { db.close() } just Runs

        assertEquals(
            TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
            backup.performBackup(packageInfo, data, FLAG_INCREMENTAL)
        )
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `non-incremental backup with data clears old data first`() = runBlocking {
        every { dbManager.deleteDb(packageName) } returns true
        singleRecordBackup(true)
        every { data.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL)
        )
        assertTrue(backup.hasState)

        assertEquals(apkBackupData, backup.finishBackup())
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `package with no new data comes back ok right away (if we have data)`() = runBlocking {
        every { dbManager.existsDb(packageName) } returns true
        every { dbManager.getDb(packageName) } returns db
        every { data.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performBackup(packageInfo, data, FLAG_DATA_NOT_CHANGED)
        )
        assertTrue(backup.hasState)

        uploadData() // we still "upload", so old data gets into new snapshot

        assertEquals(apkBackupData, backup.finishBackup())
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `request non-incremental backup when no data has changed, but we lost it`() = runBlocking {
        every { dbManager.existsDb(packageName) } returns false
        every { dbManager.getDb(packageName) } returns db
        every { db.close() } just Runs
        every { data.close() } just Runs

        assertEquals(
            TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
            backup.performBackup(packageInfo, data, FLAG_DATA_NOT_CHANGED)
        )
        assertFalse(backup.hasState) // gets cleared

        verify {
            db.close()
            data.close()
        }
    }

    @Test
    fun `exception while reading next header`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } throws IOException()
        every { db.close() } just Runs
        every { data.close() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState)

        verify {
            db.close()
            data.close()
        }
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
        every { data.close() } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `no data records`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(false))
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        every { db.close() } just Runs

        // if there's no data, the system wouldn't call us, so no special handling here
        uploadData()

        assertEquals(apkBackupData, backup.finishBackup())
        assertFalse(backup.hasState)

        verify {
            db.close()
            data.close()
        }
    }

    @Test
    fun `null data deletes key`() = runBlocking {
        initPlugin(true)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true andThen false
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns -1 // just documented by example code in LocalTransport
        every { db.delete(key) } just Runs
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        uploadData()

        assertEquals(apkBackupData, backup.finishBackup())
        assertFalse(backup.hasState)

        verify { data.close() }
    }

    @Test
    fun `exception while uploading data`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true, false))
        every { db.put(key, dataValue) } just Runs
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState)

        every { db.vacuum() } just Runs
        every { db.close() } just Runs
        every { dbManager.getDbInputStream(packageName) } returns inputStream
        coEvery {
            backupReceiver.readFromStream("KV $packageName", inputStream)
        } throws IOException()

        assertThrows<IOException> { // we let exceptions bubble up to coordinators
            backup.finishBackup()
        }
        assertFalse(backup.hasState)

        verify {
            db.close()
            data.close()
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
        every { dbManager.getDbInputStream(packageName) } returns inputStream
        coEvery {
            backupReceiver.readFromStream("KV $packageName", inputStream)
        } returns apkBackupData
    }

}
