/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.content.ContentResolver
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.content.ContentFile
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.db.CachedFile
import org.calyxos.backup.storage.db.FilesCache
import org.calyxos.backup.storage.getRandomDocFile
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.backup.storage.sameCachedFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

internal class SmallFileBackupTest {

    private val contentResolver: ContentResolver = mockk()
    private val filesCache: FilesCache = mockk()
    private val zipChunker: ZipChunker = mockk()

    private val smallFileBackup = SmallFileBackup(contentResolver, filesCache, zipChunker, true)

    private val fileInputStream: InputStream = mockk()

    init {
        mockLog()
    }

    @Test
    fun `unchanged file doesn't get backed up`(): Unit = runBlocking {
        val files = listOf(getRandomDocFile())
        val availableChunkIds = hashSetOf(getRandomString(6), getRandomString(6))
        val cachedFile = files[0].toCachedFile(listOf(availableChunkIds.iterator().next()), 1)
        val backupFile = files[0].toBackupFile(cachedFile.chunks, cachedFile.zipIndex)

        every { filesCache.getByUri(files[0].uri) } returns cachedFile

        val result = smallFileBackup.backupFiles(files, availableChunkIds, null)
        assertEquals(cachedFile.chunks.toSet(), result.chunkIds)
        assertEquals(1, result.backupDocumentFiles.size)
        assertEquals(backupFile, result.backupDocumentFiles[0])
        assertEquals(0, result.backupMediaFiles.size)
    }

    @Test
    fun `changed file gets backed up`(): Unit = runBlocking {
        val file = getRandomDocFile()
        val files = listOf(file)
        val availableChunkIds = hashSetOf(getRandomString(6))
        val cachedFile =
            file.toCachedFile(availableChunkIds.toList(), 5).copy(size = Random.nextLong())

        singleFileBackup(files, cachedFile, availableChunkIds)
    }

    @Test
    fun `new file gets backed up`(): Unit = runBlocking {
        val file = getRandomDocFile()
        val files = listOf(file)
        val availableChunkIds = hashSetOf(getRandomString(6))

        singleFileBackup(files, null, availableChunkIds)
    }

    @Test
    fun `unchanged file recovers missing chunks`(): Unit = runBlocking {
        val file = getRandomDocFile()
        val files = listOf(file)
        val missingChunk = getRandomString(6)
        val cachedFile = file.toCachedFile(listOf(missingChunk), 5)
        val availableChunkIds = hashSetOf(getRandomString(6))

        singleFileBackup(files, cachedFile, availableChunkIds)
    }

    private suspend fun singleFileBackup(
        files: List<DocFile>,
        cachedFile: CachedFile?,
        availableChunkIds: HashSet<String>,
    ) {
        val file = files[0]
        val zipChunk = ZipChunk(getRandomString(6), listOf(file), Random.nextLong(), true)
        val newCachedFile = file.toCachedFile(listOf(zipChunk.id), 1)
        val backupFile = file.toBackupFile(newCachedFile.chunks, newCachedFile.zipIndex)
        val missingChunks =
            if (cachedFile == null) emptyList() else cachedFile.chunks - availableChunkIds

        addFile(file, cachedFile)
        coEvery { zipChunker.finalizeAndReset(missingChunks) } returns zipChunk
        every { filesCache.upsert(sameCachedFile(newCachedFile)) } just Runs

        val result = smallFileBackup.backupFiles(files, availableChunkIds, null)
        assertEquals(newCachedFile.chunks.toSet(), result.chunkIds)
        assertEquals(1, result.backupDocumentFiles.size)
        assertEquals(backupFile, result.backupDocumentFiles[0])
        assertEquals(0, result.backupMediaFiles.size)
    }

    @Test
    fun `first of two new files throws and gets ignored`(): Unit = runBlocking {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val files = listOf(file1, file2)
        val availableChunkIds = hashSetOf(getRandomString(6))

        val zipChunk = ZipChunk(getRandomString(6), listOf(file2), Random.nextLong(), true)
        val cachedFile2 = file2.toCachedFile(listOf(zipChunk.id), 1)
        val backupFile = file2.toBackupFile(cachedFile2.chunks, cachedFile2.zipIndex)

        every { filesCache.getByUri(file1.uri) } returns null
        every { contentResolver.openInputStream(file1.uri) } throws IOException()
        addFile(file2)
        coEvery { zipChunker.finalizeAndReset(emptyList()) } returns zipChunk
        every { filesCache.upsert(sameCachedFile(cachedFile2)) } just Runs

        val result = smallFileBackup.backupFiles(files, availableChunkIds, null)
        assertEquals(cachedFile2.chunks.toSet(), result.chunkIds)
        assertEquals(1, result.backupDocumentFiles.size)
        assertEquals(backupFile, result.backupDocumentFiles[0])
        assertEquals(0, result.backupMediaFiles.size)
    }

    @Test
    fun `two files get put into two chunks if they are too big`(): Unit = runBlocking {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val files = listOf(file1, file2)

        val zipChunk1 = ZipChunk(getRandomString(6), listOf(file1), Random.nextLong(), true)
        val zipChunk2 = ZipChunk(getRandomString(6), listOf(file2), Random.nextLong(), true)
        val cachedFile1 = file1.toCachedFile(listOf(zipChunk1.id), 1)
        val cachedFile2 = file2.toCachedFile(listOf(zipChunk2.id), 1)
        val backupFile1 = file1.toBackupFile(cachedFile1.chunks, cachedFile1.zipIndex)
        val backupFile2 = file2.toBackupFile(cachedFile2.chunks, cachedFile2.zipIndex)

        addFile(file1)
        every { zipChunker.fitsFile(file2) } returns false
        coEvery { zipChunker.finalizeAndReset(emptyList()) } returns zipChunk1 andThen zipChunk2
        every { filesCache.upsert(sameCachedFile(cachedFile1)) } just Runs
        addFile(file2)
        // zipChunker.finalizeAndReset defined above for both files
        every { filesCache.upsert(sameCachedFile(cachedFile2)) } just Runs

        val result = smallFileBackup.backupFiles(files, hashSetOf(), null)
        assertEquals(listOf(zipChunk1.id, zipChunk2.id).toSet(), result.chunkIds)
        assertEquals(
            listOf(backupFile1, backupFile2).sortedBy { it.name },
            result.backupDocumentFiles.sortedBy { it.name }
        )
        assertEquals(0, result.backupMediaFiles.size)
    }

    @Test
    fun `two files get put into into the same chunk if they fit`(): Unit = runBlocking {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val files = listOf(file1, file2)

        val zipChunk = ZipChunk(getRandomString(6), listOf(file1, file2), Random.nextLong(), true)
        val cachedFile1 = file1.toCachedFile(listOf(zipChunk.id), 1)
        val cachedFile2 = file2.toCachedFile(listOf(zipChunk.id), 2)
        val backupFile1 = file1.toBackupFile(cachedFile1.chunks, cachedFile1.zipIndex)
        val backupFile2 = file2.toBackupFile(cachedFile2.chunks, cachedFile2.zipIndex)

        addFile(file1)
        every { zipChunker.fitsFile(file2) } returns true
        addFile(file2)
        coEvery { zipChunker.finalizeAndReset(emptyList()) } returns zipChunk
        every { filesCache.upsert(sameCachedFile(cachedFile1)) } just Runs
        every { filesCache.upsert(sameCachedFile(cachedFile2)) } just Runs

        val result = smallFileBackup.backupFiles(files, hashSetOf(), null)
        assertEquals(listOf(zipChunk.id).toSet(), result.chunkIds)
        assertEquals(
            listOf(backupFile1, backupFile2).sortedBy { it.name },
            result.backupDocumentFiles.sortedBy { it.name }
        )
        assertEquals(0, result.backupMediaFiles.size)
    }

    @Test
    fun `two files don't get backed up when finalize throws`(): Unit = runBlocking {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val files = listOf(file1, file2)

        addFile(file1)
        every { zipChunker.fitsFile(file2) } returns true
        addFile(file2)
        coEvery { zipChunker.finalizeAndReset(emptyList()) } throws IOException()

        val result = smallFileBackup.backupFiles(files, hashSetOf(), null)
        assertEquals(emptySet<String>(), result.chunkIds)
        assertEquals(0, result.backupDocumentFiles.size)
        assertEquals(0, result.backupMediaFiles.size)
    }

    private fun addFile(file: ContentFile, cachedFile: CachedFile? = null) {
        every { filesCache.getByUri(file.uri) } returns cachedFile
        every { contentResolver.openInputStream(file.uri) } returns fileInputStream
        every { zipChunker.addFile(file, fileInputStream) } just Runs
        every { fileInputStream.close() } just Runs
    }

}
