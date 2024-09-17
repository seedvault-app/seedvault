/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.ANCESTRAL_RECORD_KEY
import com.stevesoltys.seedvault.GLOBAL_METADATA_KEY
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.transport.backup.KVDb
import com.stevesoltys.seedvault.transport.backup.KvDbManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.random.Random

internal class KVRestoreTest : RestoreTest() {

    private val backendManager: BackendManager = mockk()
    private val loader = mockk<Loader>()
    private val dbManager = mockk<KvDbManager>()
    private val output = mockk<BackupDataOutput>()
    private val restore = KVRestore(
        backendManager = backendManager,
        loader = loader,
        legacyPlugin = mockk(),
        outputFactory = outputFactory,
        headerReader = mockk(),
        crypto = mockk(),
        dbManager = dbManager,
    )

    private val db = mockk<KVDb>()
    private val blobHandles = listOf(blobHandle1)

    private val key = "Restore Key"
    private val key2 = "Restore Key2"
    private val data2 = getRandomByteArray()

    @Test
    fun `getRestoreData() throws without initializing state`() {
        coAssertThrows(IllegalStateException::class.java) {
            restore.getRestoreData(fileDescriptor)
        }
    }

    @Test
    fun `loader#loadFiles() throws`() = runBlocking {
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } throws GeneralSecurityException()
        every { dbManager.deleteDb(packageInfo.packageName, true) } returns true
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))

        verifyAll {
            fileDescriptor.close()
            dbManager.deleteDb(packageInfo.packageName, true)
        }
    }

    @Test
    fun `writeEntityHeader throws`() = runBlocking {
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } returns inputStream
        every { inputStream.read(any()) } returns -1 // the DB we'll mock below
        every {
            dbManager.getDbOutputStream(packageInfo.packageName)
        } returns ByteArrayOutputStream()
        every { dbManager.getDb(packageInfo.packageName, true) } returns db
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns output
        every { db.getAll() } returns listOf(Pair(key, data))
        every { output.writeEntityHeader(key, data.size) } throws IOException()
        every { dbManager.deleteDb(packageInfo.packageName, true) } returns true
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()

        verify {
            dbManager.deleteDb(packageInfo.packageName, true)
        }
    }

    @Test
    fun `two records get restored`() = runBlocking {
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } returns inputStream
        every { inputStream.read(any()) } returns -1 // the DB we'll mock below
        every {
            dbManager.getDbOutputStream(packageInfo.packageName)
        } returns ByteArrayOutputStream()
        every { dbManager.getDb(packageInfo.packageName, true) } returns db
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns output
        every { db.getAll() } returns listOf(
            Pair(key, data),
            Pair(key2, data2)
        )
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        every { output.writeEntityHeader(key2, data2.size) } returns 42
        every { output.writeEntityData(data2, data2.size) } returns data2.size

        every { db.close() } just Runs
        every { dbManager.deleteDb(packageInfo.packageName, true) } returns true
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()

        verify {
            output.writeEntityHeader(key, data.size)
            output.writeEntityData(data, data.size)
            output.writeEntityHeader(key2, data2.size)
            output.writeEntityData(data2, data2.size)
            db.close()
            dbManager.deleteDb(packageInfo.packageName, true)
        }
    }

    @Test
    fun `auto restore uses cached DB`() = runBlocking {
        val pmPackageInfo = PackageInfo().apply {
            packageName = MAGIC_PACKAGE_MANAGER
        }
        restore.initializeState(2, pmPackageInfo, blobHandles, packageInfo)

        every { dbManager.existsDb(MAGIC_PACKAGE_MANAGER) } returns true
        every { dbManager.getDb(MAGIC_PACKAGE_MANAGER) } returns db
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns output
        every { db.getAll() } returns listOf(
            Pair(ANCESTRAL_RECORD_KEY, data),
            Pair(GLOBAL_METADATA_KEY, data),
            Pair(packageName, data2),
            Pair("foo", Random.nextBytes(23)), // should get filtered out
            Pair("bar", Random.nextBytes(42)), // should get filtered out
        )
        every { output.writeEntityHeader(ANCESTRAL_RECORD_KEY, data.size) } returns data.size
        every { output.writeEntityHeader(GLOBAL_METADATA_KEY, data.size) } returns data.size
        every { output.writeEntityHeader(packageName, data2.size) } returns data2.size
        every { output.writeEntityData(data, data.size) } returns data.size
        every { output.writeEntityData(data2, data2.size) } returns data2.size
        every { db.close() } just Runs

        every { dbManager.deleteDb(MAGIC_PACKAGE_MANAGER, true) } returns true
        every { fileDescriptor.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))

        verify(exactly = 0) {
            output.writeEntityHeader("foo", any())
            output.writeEntityHeader("bar", any())
        }
        verify {
            fileDescriptor.close()
            db.close()
        }
    }

    private fun streamsGetClosed() {
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs
    }

    private fun verifyStreamWasClosed() {
        verify {
            inputStream.close()
            fileDescriptor.close()
        }
    }

}
