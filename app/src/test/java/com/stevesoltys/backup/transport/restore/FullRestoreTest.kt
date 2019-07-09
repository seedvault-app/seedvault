package com.stevesoltys.backup.transport.restore

import android.app.backup.BackupTransport.*
import com.stevesoltys.backup.getRandomByteArray
import com.stevesoltys.backup.header.UnsupportedVersionException
import com.stevesoltys.backup.header.VERSION
import com.stevesoltys.backup.header.VersionHeader
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.random.Random

internal class FullRestoreTest : RestoreTest() {

    private val plugin = mockk<FullRestorePlugin>()
    private val restore = FullRestore(plugin, outputFactory, headerReader, crypto)

    private val encrypted = getRandomByteArray()
    private val outputStream = ByteArrayOutputStream()
    private val versionHeader = VersionHeader(VERSION, packageInfo.packageName)

    @Test
    fun `has no initial state`() {
        assertFalse(restore.hasState())
    }

    @Test
    fun `hasDataForPackage() delegates to plugin`() {
        val result = Random.nextBoolean()
        every { plugin.hasDataForPackage(token, packageInfo) } returns result
        assertEquals(result, restore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun `initializing state leaves a state`() {
        assertFalse(restore.hasState())
        restore.initializeState(token, packageInfo)
        assertTrue(restore.hasState())
    }

    @Test
    fun `getting chunks without initializing state throws`() {
        assertFalse(restore.hasState())
        assertThrows(IllegalStateException::class.java) {
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        }
    }

    @Test
    fun `getting InputStream for package when getting first chunk throws`() {
        restore.initializeState(token, packageInfo)

        every { plugin.getInputStreamForPackage(token, packageInfo) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `reading version header when getting first chunk throws`() {
        restore.initializeState(token, packageInfo)

        every { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `reading unsupported version when getting first chunk`() {
        restore.initializeState(token, packageInfo)

        every { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws UnsupportedVersionException(unsupportedVersion)

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting version header when getting first chunk throws`() {
        restore.initializeState(token, packageInfo)

        every { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting version header when getting first chunk throws security exception`() {
        restore.initializeState(token, packageInfo)

        every { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } throws SecurityException()

        assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting segment throws IOException`() {
        restore.initializeState(token, packageInfo)

        initInputStream()
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } throws IOException()
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting segment throws EOFException`() {
        restore.initializeState(token, packageInfo)

        initInputStream()
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } throws EOFException()
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs

        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `full chunk gets encrypted`() {
        restore.initializeState(token, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)
        every { inputStream.close() } just Runs

        assertEquals(encrypted.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encrypted, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState())
    }

    @Test
    fun `aborting full restore closes stream, resets state`() {
        restore.initializeState(token, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)

        restore.getNextFullRestoreDataChunk(fileDescriptor)

        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.abortFullRestore())
        assertFalse(restore.hasState())
    }

    private fun initInputStream() {
        every { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } returns versionHeader
    }

    private fun readAndEncryptInputStream(encryptedBytes: ByteArray) {
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } returns encryptedBytes
        every { fileDescriptor.close() } just Runs
    }

}
