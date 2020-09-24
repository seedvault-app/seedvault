package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.VersionHeader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVRestoreTest : RestoreTest() {

    private val plugin = mockk<KVRestorePlugin>()
    private val output = mockk<BackupDataOutput>()
    private val restore = KVRestore(plugin, outputFactory, headerReader, crypto)

    private val key = "Restore Key"
    private val key64 = key.encodeBase64()
    private val versionHeader = VersionHeader(VERSION, packageInfo.packageName, key)
    private val key2 = "Restore Key2"
    private val key264 = key2.encodeBase64()
    private val versionHeader2 = VersionHeader(VERSION, packageInfo.packageName, key2)

    @Test
    fun `hasDataForPackage() delegates to plugin`() = runBlocking {
        val result = Random.nextBoolean()

        coEvery { plugin.hasDataForPackage(token, packageInfo) } returns result

        assertEquals(result, restore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun `getRestoreData() throws without initializing state`() {
        coAssertThrows(IllegalStateException::class.java) {
            restore.getRestoreData(fileDescriptor)
        }
    }

    @Test
    fun `listing records throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.listRecords(token, packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
    }

    @Test
    fun `reading VersionHeader with unsupported version throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every {
            headerReader.readVersion(inputStream)
        } throws UnsupportedVersionException(unsupportedVersion)
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `error reading VersionHeader throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting segment throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } returns versionHeader
        every { crypto.decryptMultipleSegments(inputStream) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting header throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting header throws security exception`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } throws SecurityException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing header throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } returns versionHeader
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing value throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } returns versionHeader
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing value succeeds`() = runBlocking {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } returns versionHeader
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing two values succeeds`() = runBlocking {
        val data2 = getRandomByteArray()
        val inputStream2 = mockk<InputStream>()
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput(listOf(key64, key264))
        // first key/value
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream,
                VERSION,
                packageInfo.packageName,
                key
            )
        } returns versionHeader
        every { crypto.decryptMultipleSegments(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        // second key/value
        coEvery { plugin.getInputStreamForRecord(token, packageInfo, key264) } returns inputStream2
        every { headerReader.readVersion(inputStream2) } returns VERSION
        every {
            crypto.decryptHeader(
                inputStream2,
                VERSION,
                packageInfo.packageName,
                key2
            )
        } returns versionHeader2
        every { crypto.decryptMultipleSegments(inputStream2) } returns data2
        every { output.writeEntityHeader(key2, data2.size) } returns 42
        every { output.writeEntityData(data2, data2.size) } returns data2.size
        every { inputStream2.close() } just Runs
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
    }

    private fun getRecordsAndOutput(recordKeys: List<String> = listOf(key64)) {
        coEvery { plugin.listRecords(token, packageInfo) } returns recordKeys
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
