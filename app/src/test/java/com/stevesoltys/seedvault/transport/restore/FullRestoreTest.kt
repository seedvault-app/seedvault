package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.VersionHeader
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
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
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
    fun `hasDataForPackage() delegates to plugin`() = runBlocking {
        val result = Random.nextBoolean()
        coEvery { plugin.hasDataForPackage(token, packageInfo) } returns result
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
        coAssertThrows(IllegalStateException::class.java) {
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        }
    }

    @Test
    fun `getting InputStream for package when getting first chunk throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `reading version header when getting first chunk throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `reading unsupported version when getting first chunk`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws UnsupportedVersionException(unsupportedVersion)

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting version header when getting first chunk throws`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } throws IOException()

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting version header when getting first chunk throws security exception`() = runBlocking {
        restore.initializeState(token, packageInfo)

        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } throws SecurityException()

        assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting segment throws IOException`() = runBlocking {
        restore.initializeState(token, packageInfo)

        initInputStream()
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } throws IOException()
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs

        assertEquals(TRANSPORT_PACKAGE_REJECTED, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `decrypting segment throws EOFException`() = runBlocking {
        restore.initializeState(token, packageInfo)

        initInputStream()
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } throws EOFException()
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs

        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
    }

    @Test
    fun `full chunk gets encrypted`() = runBlocking {
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
    fun `aborting full restore closes stream, resets state`() = runBlocking {
        restore.initializeState(token, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)

        restore.getNextFullRestoreDataChunk(fileDescriptor)

        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.abortFullRestore())
        assertFalse(restore.hasState())
    }

    private fun initInputStream() {
        coEvery { plugin.getInputStreamForPackage(token, packageInfo) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName) } returns versionHeader
    }

    private fun readAndEncryptInputStream(encryptedBytes: ByteArray) {
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        every { crypto.decryptSegment(inputStream) } returns encryptedBytes
        every { fileDescriptor.close() } just Runs
    }

}
