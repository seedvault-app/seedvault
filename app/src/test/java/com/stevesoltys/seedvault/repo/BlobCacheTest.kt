/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.content.Context
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path

internal class BlobCacheTest : TransportTest() {

    private val strictContext: Context = mockk()

    private val blobCache = BlobCache(context)

    @Test
    fun `write to and read from cache`(@TempDir tmpDir: Path) {
        val file = File(tmpDir.toString(), "tmpCache")
        BlobCache(strictContext).saveTwoBlobsToCache(file)

        BlobCache(strictContext).let { cache ->
            // old blobs are not yet in new cache
            assertNull(cache[chunkId1])
            assertNull(cache[chunkId2])

            // read saved blobs from cache
            every { strictContext.openFileInput(any()) } returns file.inputStream()
            cache.populateCache(listOf(fileInfo1, fileInfo2), emptyList())

            // now both blobs are in the map
            assertEquals(blob1, cache[chunkId1])
            assertEquals(blob2, cache[chunkId2])

            // after clearing, blobs are gone
            cache.clear()
            assertNull(cache[chunkId1])
            assertNull(cache[chunkId2])
        }
    }

    @Test
    fun `cached blob gets only used if on backend`(@TempDir tmpDir: Path) {
        val file = File(tmpDir.toString(), "tmpCache")
        BlobCache(strictContext).saveTwoBlobsToCache(file)

        BlobCache(strictContext).let { cache ->
            // read saved blobs from cache
            every { strictContext.openFileInput(any()) } returns file.inputStream()
            cache.populateCache(listOf(fileInfo2), emptyList()) // fileInfo1 is missing

            // now only blob2 gets used, because blob1 wasn't on backend
            assertNull(cache[chunkId1])
            assertEquals(blob2, cache[chunkId2])
        }
    }

    @Test
    fun `cached blob gets only used if same size on backend`(@TempDir tmpDir: Path) {
        val file = File(tmpDir.toString(), "tmpCache")
        BlobCache(strictContext).saveTwoBlobsToCache(file)

        val info = fileInfo1.copy(size = fileInfo1.size - 1)

        BlobCache(strictContext).let { cache ->
            // read saved blobs from cache
            every { strictContext.openFileInput(any()) } returns file.inputStream()
            cache.populateCache(listOf(info, fileInfo2), emptyList()) // info has different size now

            // now only blob2 gets used, because blob1 wasn't on backend
            assertNull(cache[chunkId1])
            assertEquals(blob2, cache[chunkId2])
        }
    }

    @Test
    fun `blobs from snapshot get added to cache`() {
        assertEquals(blob1, snapshot.blobsMap[chunkId1])
        assertEquals(blob2, snapshot.blobsMap[chunkId2])

        // before populating cache, the blobs are not in
        assertNull(blobCache[chunkId1])
        assertNull(blobCache[chunkId2])

        blobCache.populateCache(listOf(fileInfo1, fileInfo2), listOf(snapshot))

        // after populating cache, the blobs are in
        assertEquals(blob1, blobCache[chunkId1])
        assertEquals(blob2, blobCache[chunkId2])

        // clearing cache removes blobs
        blobCache.clear()
        assertNull(blobCache[chunkId1])
        assertNull(blobCache[chunkId2])
    }

    @Test
    fun `blobs from snapshot get added to cache only if on backend`() {
        blobCache.populateCache(listOf(fileInfo2), listOf(snapshot))

        // after populating cache, only second blob is in
        assertNull(blobCache[chunkId1])
        assertEquals(blob2, blobCache[chunkId2])
    }

    @Test
    fun `blobs from snapshot get added to cache only if same size on backend`() {
        val info = fileInfo1.copy(size = fileInfo1.size - 1) // same blob, different size
        blobCache.populateCache(listOf(info, fileInfo2), listOf(snapshot))

        // after populating cache, only second blob is in
        assertNull(blobCache[chunkId1])
        assertEquals(blob2, blobCache[chunkId2])
    }

    @Test
    fun `test clearing loading cache`() {
        // clearing the local cache, deletes the cache file
        every { strictContext.deleteFile(any()) } returns true
        blobCache.clearLocalCache()
    }

    private fun BlobCache.saveTwoBlobsToCache(file: File) {
        every { strictContext.openFileOutput(any(), any()) } answers {
            FileOutputStream(file, true)
        }

        // save new blobs (using a new output stream for each as it gets closed)
        saveNewBlob(chunkId1, blob1)
        saveNewBlob(chunkId2, blob2)

        // clearing cache should affect persisted blobs
        clear()
    }
}
