/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.header.getADForFull
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
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

@Suppress("DEPRECATION")
internal class FullRestoreV1Test : RestoreTest() {

    private val backendManager: BackendManager = mockk()
    private val backend = mockk<Backend>()
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val restore = FullRestore(
        backendManager = backendManager,
        loader = mockk(),
        legacyPlugin = legacyPlugin,
        outputFactory = outputFactory,
        headerReader = headerReader,
        crypto = crypto,
    )

    private val encrypted = getRandomByteArray()
    private val outputStream = ByteArrayOutputStream()
    private val ad = getADForFull(1, packageInfo.packageName)

    init {
        every { backendManager.backend } returns backend
    }

    @Test
    fun `has no initial state`() {
        assertFalse(restore.hasState)
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
        assertFalse(restore.hasState)
        restore.initializeStateV1(token, name, packageInfo)
        assertTrue(restore.hasState)
    }

    @Test
    fun `getting chunks without initializing state throws`() {
        assertFalse(restore.hasState)
        coAssertThrows(IllegalStateException::class.java) {
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        }
    }

    @Test
    fun `getting InputStream for package when getting first chunk throws`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `reading version header when getting first chunk throws`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `reading unsupported version when getting first chunk`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every {
            headerReader.readVersion(inputStream, 1)
        } throws UnsupportedVersionException(unsupportedVersion)
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `getting decrypted stream when getting first chunk throws`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )
    }

    @Test
    fun `getting decrypted stream when getting first chunk throws general security exception`() =
        runBlocking {
            restore.initializeStateV1(token, name, packageInfo)

            coEvery { backend.load(handle) } returns inputStream
            every { headerReader.readVersion(inputStream, 1) } returns 1
            every {
                crypto.newDecryptingStreamV1(inputStream, ad)
            } throws GeneralSecurityException()
            every { fileDescriptor.close() } just Runs

            assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))
        }

    @Test
    fun `full chunk gets decrypted`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)
        every { inputStream.close() } just Runs

        assertEquals(encrypted.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encrypted, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState)
    }

    @Test
    @Suppress("deprecation")
    fun `full chunk gets decrypted from version 0`() = runBlocking {
        restore.initializeStateV0(token, packageInfo)

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
        assertFalse(restore.hasState)
    }

    @Test
    fun `three full chunk get decrypted and then return no more data`() = runBlocking {
        val encryptedBytes = Random.nextBytes(MAX_SEGMENT_LENGTH * 2 + 1)
        val decryptedInputStream = ByteArrayInputStream(encryptedBytes)
        restore.initializeStateV1(token, name, packageInfo)

        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } returns decryptedInputStream
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
        assertFalse(restore.hasState)
    }

    @Test
    fun `aborting full restore closes stream, resets state`() = runBlocking {
        restore.initializeStateV1(token, name, packageInfo)

        initInputStream()
        readAndEncryptInputStream(encrypted)

        restore.getNextFullRestoreDataChunk(fileDescriptor)

        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.abortFullRestore())
        assertFalse(restore.hasState)
    }

    private fun initInputStream() {
        coEvery { backend.load(handle) } returns inputStream
        every { headerReader.readVersion(inputStream, 1) } returns 1
        every { crypto.newDecryptingStreamV1(inputStream, ad) } returns decryptedInputStream
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
