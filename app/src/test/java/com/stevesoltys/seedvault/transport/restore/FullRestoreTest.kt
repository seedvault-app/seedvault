/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
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
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.repo.Loader
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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

internal class FullRestoreTest : RestoreTest() {

    private val backendManager: BackendManager = mockk()
    private val backend = mockk<Backend>()
    private val loader = mockk<Loader>()
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val restore = FullRestore(
        backendManager = backendManager,
        loader = loader,
        legacyPlugin = legacyPlugin,
        outputFactory = outputFactory,
        headerReader = headerReader,
        crypto = crypto,
    )

    private val encrypted = getRandomByteArray()
    private val outputStream = ByteArrayOutputStream()
    private val blobHandles = listOf(blobHandle1)

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
        restore.initializeState(VERSION, packageInfo, blobHandles)
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
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } throws IOException()
        every { fileDescriptor.close() } just Runs

        assertEquals(
            TRANSPORT_PACKAGE_REJECTED,
            restore.getNextFullRestoreDataChunk(fileDescriptor)
        )

        verify { fileDescriptor.close() }
    }

    @Test
    fun `reading from stream throws general security exception`() = runBlocking {
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } throws GeneralSecurityException()
        every { fileDescriptor.close() } just Runs

        assertEquals(TRANSPORT_ERROR, restore.getNextFullRestoreDataChunk(fileDescriptor))

        verify { fileDescriptor.close() }
    }

    @Test
    fun `full chunk gets decrypted`() = runBlocking {
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } returns inputStream
        readInputStream(encrypted)
        every { inputStream.close() } just Runs

        assertEquals(encrypted.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertArrayEquals(encrypted, outputStream.toByteArray())
        restore.finishRestore()
        assertFalse(restore.hasState)
    }

    @Test
    fun `larger data gets decrypted and then return no more data`() = runBlocking {
        val encryptedBytes = Random.nextBytes(MAX_SEGMENT_LENGTH * 2 + 1)
        val decryptedInputStream = ByteArrayInputStream(encryptedBytes)
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } returns decryptedInputStream
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
        restore.initializeState(VERSION, packageInfo, blobHandles)

        coEvery { loader.loadFiles(blobHandles) } returns inputStream
        readInputStream(encrypted)

        restore.getNextFullRestoreDataChunk(fileDescriptor)

        every { inputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, restore.abortFullRestore())
        assertFalse(restore.hasState)
    }

    private fun readInputStream(encryptedBytes: ByteArray) {
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        val slot = CapturingSlot<ByteArray>()
        every { inputStream.read(capture(slot)) } answers {
            encryptedBytes.copyInto(slot.captured)
            encryptedBytes.size
        }
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs
    }

}
