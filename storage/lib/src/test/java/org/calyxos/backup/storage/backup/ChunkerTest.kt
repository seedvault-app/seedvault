/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import org.calyxos.backup.storage.crypto.ChunkCrypto
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

internal class ChunkerTest {

    private val chunkId0Byte1 = "6620b31f2924b8c01547745f41825d322336f83ebb13d723678789d554d8a3ef"
    private val chunkId0Byte2 = "b31825742c7dffdfdcea617f81eadcfb55e1f85f3a260abf40f38e6d00472c5a"
    private val chunkId0Byte3 = "e7655205039eddec669bdc5099863024deef1a609430f10dac5f7889f03bf139"
    private val chunkId0Byte4 = "aa7855e13839dd767cd5da7c1ff5036540c9264b7a803029315e55375287b4af"

    @Test
    fun testTwoByteChunks() {
        val mac = ChunkCrypto.getMac(ByteArray(KEY_SIZE_BYTES))
        val chunker = Chunker(mac, 2)

        val input0 = ByteArrayInputStream(ByteArray(0))
        assertEquals(emptyList<Chunk>(), chunker.makeChunks(input0))

        val input1 = ByteArrayInputStream(ByteArray(1))
        val chunk1 = Chunk(chunkId0Byte1, 0, 1)
        assertEquals(listOf(chunk1), chunker.makeChunks(input1))

        val input2 = ByteArrayInputStream(ByteArray(2))
        val chunk2 = Chunk(chunkId0Byte2, 0, 2)
        assertEquals(listOf(chunk2), chunker.makeChunks(input2))

        val input3 = ByteArrayInputStream(ByteArray(3))
        val chunk3a = Chunk(chunkId0Byte2, 0, 2)
        val chunk3b = Chunk(chunkId0Byte1, 2, 1)
        assertEquals(listOf(chunk3a, chunk3b), chunker.makeChunks(input3))

        val input4 = ByteArrayInputStream(ByteArray(4))
        val chunk4a = Chunk(chunkId0Byte2, 0, 2)
        val chunk4b = Chunk(chunkId0Byte2, 2, 2)
        assertEquals(listOf(chunk4a, chunk4b), chunker.makeChunks(input4))

        val input5 = ByteArrayInputStream(ByteArray(5))
        val chunk5a = Chunk(chunkId0Byte2, 0, 2)
        val chunk5b = Chunk(chunkId0Byte2, 2, 2)
        val chunk5c = Chunk(chunkId0Byte1, 4, 1)
        assertEquals(listOf(chunk5a, chunk5b, chunk5c), chunker.makeChunks(input5))

        val input6 = ByteArrayInputStream(ByteArray(6))
        val chunk6a = Chunk(chunkId0Byte2, 0, 2)
        val chunk6b = Chunk(chunkId0Byte2, 2, 2)
        val chunk6c = Chunk(chunkId0Byte2, 4, 2)
        assertEquals(listOf(chunk6a, chunk6b, chunk6c), chunker.makeChunks(input6))
    }

    @Test
    fun testThreeByteChunks() {
        val mac = ChunkCrypto.getMac(ByteArray(KEY_SIZE_BYTES))
        val chunker = Chunker(mac, 3)

        val input0 = ByteArrayInputStream(ByteArray(0))
        assertEquals(emptyList<Chunk>(), chunker.makeChunks(input0))

        val input1 = ByteArrayInputStream(ByteArray(1))
        val chunk1 = Chunk(chunkId0Byte1, 0, 1)
        assertEquals(listOf(chunk1), chunker.makeChunks(input1))

        val input2 = ByteArrayInputStream(ByteArray(2))
        val chunk2 = Chunk(chunkId0Byte2, 0, 2)
        assertEquals(listOf(chunk2), chunker.makeChunks(input2))

        val input3 = ByteArrayInputStream(ByteArray(3))
        val chunk3 = Chunk(chunkId0Byte3, 0, 3)
        assertEquals(listOf(chunk3), chunker.makeChunks(input3))

        val input4 = ByteArrayInputStream(ByteArray(4))
        val chunk4a = Chunk(chunkId0Byte3, 0, 3)
        val chunk4b = Chunk(chunkId0Byte1, 3, 1)
        assertEquals(listOf(chunk4a, chunk4b), chunker.makeChunks(input4))

        val input5 = ByteArrayInputStream(ByteArray(5))
        val chunk5a = Chunk(chunkId0Byte3, 0, 3)
        val chunk5b = Chunk(chunkId0Byte2, 3, 2)
        assertEquals(listOf(chunk5a, chunk5b), chunker.makeChunks(input5))

        val input6 = ByteArrayInputStream(ByteArray(6))
        val chunk6a = Chunk(chunkId0Byte3, 0, 3)
        val chunk6b = Chunk(chunkId0Byte3, 3, 3)
        assertEquals(listOf(chunk6a, chunk6b), chunker.makeChunks(input6))
    }

    @Test
    fun testChunksGreaterThanBuffer() {
        val mac = ChunkCrypto.getMac(ByteArray(KEY_SIZE_BYTES))
        val chunker = Chunker(mac, 4, Random.nextInt(1, 4))

        val input0 = ByteArrayInputStream(ByteArray(0))
        assertEquals(emptyList<Chunk>(), chunker.makeChunks(input0))

        val input1 = ByteArrayInputStream(ByteArray(1))
        val chunk1 = Chunk(chunkId0Byte1, 0, 1)
        assertEquals(listOf(chunk1), chunker.makeChunks(input1))

        val input5 = ByteArrayInputStream(ByteArray(5))
        val chunk5a = Chunk(chunkId0Byte4, 0, 4)
        val chunk5b = Chunk(chunkId0Byte1, 4, 1)
        assertEquals(listOf(chunk5a, chunk5b), chunker.makeChunks(input5))

        val input7 = ByteArrayInputStream(ByteArray(7))
        val chunk7a = Chunk(chunkId0Byte4, 0, 4)
        val chunk7b = Chunk(chunkId0Byte3, 4, 3)
        assertEquals(listOf(chunk7a, chunk7b), chunker.makeChunks(input7))

        val input16 = ByteArrayInputStream(ByteArray(16))
        val chunk16a = Chunk(chunkId0Byte4, 0, 4)
        val chunk16b = Chunk(chunkId0Byte4, 4, 4)
        val chunk16c = Chunk(chunkId0Byte4, 8, 4)
        val chunk16d = Chunk(chunkId0Byte4, 12, 4)
        assertEquals(
            listOf(chunk16a, chunk16b, chunk16c, chunk16d),
            chunker.makeChunks(input16)
        )
    }

}
