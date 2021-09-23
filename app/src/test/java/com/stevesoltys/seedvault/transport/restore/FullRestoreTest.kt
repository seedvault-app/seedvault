package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.header.getADForFull
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class FullRestoreTest : RestoreTest() {

    private val plugin = mockk<StoragePlugin>()
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val restore = FullRestore(plugin, legacyPlugin, outputFactory, headerReader, crypto)

    private val encrypted = getRandomByteArray()
    private val outputStream = ByteArrayOutputStream()
    private val ad = getADForFull(VERSION, packageInfo.packageName)

    @Test
    fun `has no initial state`() {
        assertFalse(restore.hasState())
    }

    @Test
    @Suppress("deprecation")
    fun `v0 hasDataForPackage() delegates to plugin`() = runBlocking {
        val result = Random.nextBoolean()
        coEvery { legacyPlugin.hasDataForFullPackage(token, packageInfo) } returns result
        assertEquals(result, restore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun `initializing state leaves a state`() {
        assertFalse(restore.hasState())
        restore.initializeState(VERSION, token, name, packageInfo)
        assertTrue(restore.hasState())
    }

    @Test
    fun `getting chunks without initializing state throws`() {
        assertFalse(restore.hasState())
        coAssertThrows(IllegalStateException::class.java) {
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        }
    }

    @Test
    fun `getting InputStream for package when getting first chunk throws`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `reading version header when getting first chunk throws`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every { headerReader.readVersion(inputStream, VERSION) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `reading unsupported version when getting first chunk`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every {
            headerReader.readVersion(inputStream, VERSION)
        } throws UnsupportedVersionException(unsupportedVersion)
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `getting decrypted stream when getting first chunk throws`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every { headerReader.readVersion(inputStream, VERSION) } returns VERSION
        every { crypto.newDecryptingStream(inputStream, ad) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `getting decrypted stream when getting first chunk throws general security exception`() =
        runBlocking {
            restore.initializeState(VERSION, token, name, packageInfo)

            coEvery { plugin.getInputStream(token, name) } returns inputStream
            every { headerReader.readVersion(inputStream, VERSION) } returns VERSION
            every { crypto.newDecryptingStream(inputStream, ad) } throws GeneralSecurityException()
            every { fileDescriptor.close() } just Runs

            assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))
        }

    @Test
    fun `full chunk gets decrypted`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)
        every { inputStream.close() } just Runs

        assertEquals(encrypted.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encrypted, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState())
    }

    @Test
    @Suppress("deprecation")
    fun `full chunk gets decrypted from version 0`() = runBlocking {
        restore.initializeState(0.toByte(), token, name, packageInfo)

        coEvery { legacyPlugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream, 0.toByte()) } returns 0.toByte()
        every {
            crypto.decryptHeader(inputStream, 0.toByte(), packageInfo.packageName)
        } returns VersionHeader(0.toByte(), packageInfo.packageName)
        every { crypto.decryptSegment(inputStream) } returns encrypted

        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { fileDescriptor.close() } just Runs
        every { inputStream.close() } just Runs

        assertEquals(encrypted.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encrypted, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState())
    }

    @Test
    fun `unexpected version aborts with error`() = runBlocking {
        restore.initializeState(Byte.MAX_VALUE, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every {
            headerReader.readVersion(inputStream, Byte.MAX_VALUE)
        } throws GeneralSecurityException()
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs

        assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.abortFullRestore()
        assertFalse(restore.hasState())
    }

    @Test
    fun `three full chunk get decrypted and then return no more data`() = runBlocking {
        val encryptedBytes = Random.nextBytes(MAX_SEGMENT_LENGTH * 2 + 1)
        val decryptedInputStream = ByteArrayInputStream(encryptedBytes)
        restore.initializeState(VERSION, token, name, packageInfo)

        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every { headerReader.readVersion(inputStream, VERSION) } returns VERSION
        every { crypto.newDecryptingStream(inputStream, ad) } returns decryptedInputStream
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { fileDescriptor.close() } just Runs
        every { inputStream.close() } just Runs

        assertEquals(MAX_SEGMENT_LENGTH, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(MAX_SEGMENT_LENGTH, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(1, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encryptedBytes, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState())
    }

    @Test
    fun `aborting full restore closes stream, resets state`() = runBlocking {
        restore.initializeState(VERSION, token, name, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)

        restore.getNextFullRestoreDataChunk(fileDescriptor)

        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.abortFullRestore())
        assertFalse(restore.hasState())
    }

    private fun initInputStream() {
        coEvery { plugin.getInputStream(token, name) } returns inputStream
        every { headerReader.readVersion(inputStream, VERSION) } returns VERSION
        every { crypto.newDecryptingStream(inputStream, ad) } returns decryptedInputStream
    }

    private fun readAndEncryptInputStream(encryptedBytes: ByteArray) {
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        val slot = CapturingSlot<ByteArray>()
        every { decryptedInputStream.read(capture(slot)) } answers {
            encryptedBytes.copyInto(slot.captured)
            encryptedBytes.size
        }
        every { decryptedInputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs
    }

}
