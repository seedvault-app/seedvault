package org.calyxos.backup.storage.backup

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.calyxos.backup.storage.getRandomDocFile
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import javax.crypto.Mac
import kotlin.random.Random
import kotlin.test.assertFailsWith

internal class ZipChunkerTest {

    private val mac: Mac = mockk()
    private val chunkWriter: ChunkWriter = mockk()
    private val fileInputStream: InputStream = mockk()

    private val zipChunker = ZipChunker(mac, chunkWriter, 42)

    @Test
    fun fitsFile() {
        val zipChunker = ZipChunker(mac, chunkWriter, 10)
        for (i in (0L..10L)) {
            assertTrue(zipChunker.fitsFile(getRandomDocFile(i)))
        }
        assertFalse(zipChunker.fitsFile(getRandomDocFile(11)))
    }

    @Test
    fun addFile() {
        val file1 = getRandomDocFile()
        val file2 = getRandomDocFile()
        val file3 = getRandomDocFile()

        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1, fileInputStream)

        every { chunkWriter.writeNewZipEntry(any(), 2, fileInputStream) } just Runs
        zipChunker.addFile(file2, fileInputStream)

        every { chunkWriter.writeNewZipEntry(any(), 3, fileInputStream) } just Runs
        zipChunker.addFile(file3, fileInputStream)
    }

    @Test
    fun `throwing in addFile does not modify counter`() {
        val zipChunker = ZipChunker(mac, chunkWriter, 10)
        val file1 = getRandomDocFile(5)
        val file2 = getRandomDocFile(5)
        val file3 = getRandomDocFile(5)

        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1, fileInputStream)

        every { chunkWriter.writeNewZipEntry(any(), 2, fileInputStream) } throws IOException()
        assertFailsWith(IOException::class) {
            zipChunker.addFile(file2, fileInputStream)
        }

        // uses counter 2 again
        every { chunkWriter.writeNewZipEntry(any(), 2, fileInputStream) } just Runs
        zipChunker.addFile(file3, fileInputStream)
    }

    @Test
    fun `two files finalizeAndReset()`() {
        val file1 = getRandomDocFile(4)
        val file2 = getRandomDocFile(8)
        val chunkIdBytes = Random.nextBytes(64)
        val missingChunks = listOf(getRandomString(6), getRandomString(6))
        val wasWritten = Random.nextBoolean()
        val zipChunk = ZipChunk(chunkIdBytes.toHexString(), listOf(file1, file2), 22L)

        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1, fileInputStream)
        every { chunkWriter.writeNewZipEntry(any(), 2, fileInputStream) } just Runs
        zipChunker.addFile(file2, fileInputStream)

        every { mac.doFinal(any()) } returns chunkIdBytes
        every { chunkWriter.writeZipChunk(zipChunk, any(), missingChunks) } returns wasWritten

        assertEquals(
            zipChunk.copy(wasUploaded = wasWritten),
            zipChunker.finalizeAndReset(missingChunks)
        )

        // counter gets reset to 1
        val file1a = getRandomDocFile(4)
        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1a, fileInputStream)
    }

    @Test
    fun `throwing in finalizeAndReset() resets counter`() {
        val file1 = getRandomDocFile(4)
        val file2 = getRandomDocFile(8)
        val chunkIdBytes = Random.nextBytes(64)
        val missingChunks = listOf(getRandomString(6), getRandomString(6))
        val zipChunk = ZipChunk(chunkIdBytes.toHexString(), listOf(file1, file2), 22L)

        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1, fileInputStream)
        every { chunkWriter.writeNewZipEntry(any(), 2, fileInputStream) } just Runs
        zipChunker.addFile(file2, fileInputStream)

        every { mac.doFinal(any()) } returns chunkIdBytes
        every { chunkWriter.writeZipChunk(zipChunk, any(), missingChunks) } throws IOException()

        assertFailsWith(IOException::class) {
            zipChunker.finalizeAndReset(missingChunks)
        }

        // counter still gets reset to 1 even though we had an exception
        val file1a = getRandomDocFile(4)
        every { chunkWriter.writeNewZipEntry(any(), 1, fileInputStream) } just Runs
        zipChunker.addFile(file1a, fileInputStream)
    }

}
