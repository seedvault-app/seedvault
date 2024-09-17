/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.transport.backup.KVDb
import com.stevesoltys.seedvault.transport.backup.KvDbManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyAll
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

internal class KVRestoreV1Test : RestoreTest() {

    private val backendManager: BackendManager = mockk()
    private val loader = mockk<Loader>()
    private val backend = mockk<Backend>()
    @Suppress("DEPRECATION")
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val dbManager = mockk<KvDbManager>()
    private val output = mockk<BackupDataOutput>()
    private val restore = KVRestore(
        backendManager = backendManager,
        loader = loader,
        legacyPlugin = legacyPlugin,
        outputFactory = outputFactory,
        headerReader = headerReader,
        crypto = crypto,
        dbManager = dbManager,
    )

    private val db = mockk<KVDb>()
    private val ad = getADForKV(1, packageInfo.packageName)

    private val key = "Restore Key"
    private val key64 = key.encodeBase64()
    private val key2 = "Restore Key2"
    private val key264 = key2.encodeBase64()
    private val data2 = getRandomByteArray()

    private val outputStream = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).close()
    }
    private val decryptInputStream = ByteArrayInputStream(outputStream.toByteArray())

    init {
        // for InputStream#readBytes()
        mockkStatic("kotlin.io.ByteStreamsKt")

        every { backendManager.backend } returns backend
    }

    @Test
    fun `getRestoreData() throws without initializing state`() {
        coAssertThrows(IllegalStateException::class.java) {
            restore.getRestoreData(fileDescriptor)
        }
    }

    @Test
    fun `unexpected version aborts with error`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every {
            headerReader.readVersion(inputStream, 1)
        } throws UnsupportedVersionException(Byte.MAX_VALUE)
        every { dbManager.deleteDb(packageInfo.packageName, true) } returns true
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `newDecryptingStream throws`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } throws GeneralSecurityException()
        every { dbManager.deleteDb(packageInfo.packageName, true) } returns true
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()

        verifyAll {
            dbManager.deleteDb(packageInfo.packageName, true)
        }
    }

    @Test
    fun `writeEntityHeader throws`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } returns decryptInputStream
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
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } returns decryptInputStream
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

    //
    // v0 legacy tests below
    //

    @Test
    @Suppress("Deprecation")
    fun `v0 hasDataForPackage() delegates to plugin`() = runBlocking {
        val result = Random.nextBoolean()

        coEvery { legacyPlugin.hasDataForPackage(token, packageInfo) } returns result

        assertEquals(result, restore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun `v0 listing records throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        coEvery { legacyPlugin.listRecords(token, packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
    }

    @Test
    fun `v0 reading VersionHeader with unsupported version throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every {
            headerReader.readVersion(inputStream, 0x00)
        } throws UnsupportedVersionException(unsupportedVersion)
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `v0 error reading VersionHeader throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0x00) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("deprecation")
    fun `v0 decrypting stream throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0x00) } returns 0x00
        every {
            crypto.decryptHeader(inputStream, 0x00, packageInfo.packageName, key)
        } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("deprecation")
    fun `v0 decrypting stream throws security exception`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0x00) } returns 0x00
        every {
            crypto.decryptHeader(inputStream, 0x00, packageInfo.packageName, key)
        } returns VersionHeader(0x00, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 writing header throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream, 0x00, packageInfo.packageName, key)
        } returns VersionHeader(0x00, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("deprecation")
    fun `v0 writing value throws`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream, 0, packageInfo.packageName, key)
        } returns VersionHeader(0, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("deprecation")
    fun `v0 writing value succeeds`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream, 0, packageInfo.packageName, key)
        } returns VersionHeader(0, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("deprecation")
    fun `v0 writing value uses old v0 code`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput()
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream, 0, packageInfo.packageName, key)
        } returns VersionHeader(1, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    @Suppress("Deprecation")
    fun `v0 writing two values succeeds`() = runBlocking {
        val data2 = getRandomByteArray()
        val inputStream2 = mockk<InputStream>()
        restore.initializeStateV0(token, packageInfo)

        getRecordsAndOutput(listOf(key64, key264))
        // first key/value
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key64)
        } returns inputStream
        every { headerReader.readVersion(inputStream, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream, 0, packageInfo.packageName, key)
        } returns VersionHeader(0, packageInfo.packageName, key)
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        // second key/value
        coEvery {
            legacyPlugin.getInputStreamForRecord(token, packageInfo, key264)
        } returns inputStream2
        every { headerReader.readVersion(inputStream2, 0) } returns 0
        every {
            crypto.decryptHeader(inputStream2, 0, packageInfo.packageName, key2)
        } returns VersionHeader(0, packageInfo.packageName, key2)
        every { crypto.decryptMultipleSegments(inputStream2) } returns data2
        every { output.writeEntityHeader(key2, data2.size) } returns 42
        every { output.writeEntityData(data2, data2.size) } returns data2.size
        every { inputStream2.close() } just Runs
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
    }

    private fun getRecordsAndOutput(recordKeys: List<String> = listOf(key64)) {
        coEvery { legacyPlugin.listRecords(token, packageInfo) } returns recordKeys
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns output
    }

    private fun streamsGetClosed() {
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs
    }

    private fun verifyStreamWasClosed() {
        verifyAll {
            inputStream.close()
            fileDescriptor.close()
        }
    }

}
