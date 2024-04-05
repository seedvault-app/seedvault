/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.content.ContentResolver
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.crypto.Hkdf.KEY_SIZE_BYTES
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.db.FilesCache
import org.calyxos.backup.storage.getRandomDocFile
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.backup.storage.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Mac
import kotlin.random.Random

internal class SmallFileBackupIntegrationTest {

    private val contentResolver: ContentResolver = mockk()
    private val filesCache: FilesCache = mockk()
    private val mac: Mac = mockk()
    private val chunksCache: ChunksCache = mockk()
    private val storagePlugin: StoragePlugin = mockk()

    private val chunkWriter = ChunkWriter(
        streamCrypto = StreamCrypto,
        streamKey = Random.nextBytes(KEY_SIZE_BYTES),
        chunksCache = chunksCache,
        storagePlugin = storagePlugin,
    )
    private val zipChunker = ZipChunker(
        mac = mac,
        chunkWriter = chunkWriter,
    )

    private val smallFileBackup = SmallFileBackup(contentResolver, filesCache, zipChunker, true)

    init {
        mockLog()
    }

    /**
     * This tests that if writing out one ZIP entry throws an exception,
     * the subsequent entries can still be written.
     * Previously, we'd start a new ZipEntry with the same counter value
     * which is not allowed, so all subsequent files would also not get backed up.
     */
    @Test
    fun `first of two new files throws and gets ignored`(): Unit = runBlocking {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val files = listOf(file1, file2)
        val availableChunkIds = hashSetOf(getRandomString(6))
        val observer: BackupObserver = mockk()

        val inputStream1: InputStream = mockk()
        val inputStream2: InputStream = ByteArrayInputStream(Random.nextBytes(42))
        val outputStream2 = ByteArrayOutputStream()

        val chunkId = Random.nextBytes(KEY_SIZE_BYTES)
        val cachedFile2 = file2.toCachedFile(listOf(chunkId.toHexString()), 1)
        val backupFile = file2.toBackupFile(cachedFile2.chunks, cachedFile2.zipIndex)

        every { filesCache.getByUri(file1.uri) } returns null
        every { filesCache.getByUri(file2.uri) } returns null

        every { contentResolver.openInputStream(file1.uri) } returns inputStream1
        every { contentResolver.openInputStream(file2.uri) } returns inputStream2

        every { inputStream1.read(any<ByteArray>()) } throws IOException()
        coEvery { observer.onFileBackupError(file1, "S") } just Runs

        every { mac.doFinal(any<ByteArray>()) } returns chunkId
        every { chunksCache.get(any()) } returns null
        every { storagePlugin.getChunkOutputStream(any()) } returns outputStream2
        every {
            chunksCache.insert(match<CachedChunk> { cachedChunk ->
                cachedChunk.id == chunkId.toHexString() &&
                    cachedChunk.refCount == 0L &&
                    cachedChunk.size <= outputStream2.size() &&
                    cachedChunk.version == 0.toByte()
            })
        } just Runs
        every {
            filesCache.upsert(match {
                it.copy(lastSeen = cachedFile2.lastSeen) == cachedFile2
            })
        } just Runs
        coEvery {
            observer.onFileBackedUp(file2, true, 0, match<Long> { it <= outputStream2.size() }, "S")
        } just Runs

        val result = smallFileBackup.backupFiles(files, availableChunkIds, observer)
        assertEquals(setOf(chunkId.toHexString()), result.chunkIds)
        assertEquals(1, result.backupDocumentFiles.size)
        assertEquals(backupFile, result.backupDocumentFiles[0])
        assertEquals(0, result.backupMediaFiles.size)

        coVerify {
            observer.onFileBackedUp(file2, true, 0, match<Long> { it <= outputStream2.size() }, "S")
        }
    }

}
