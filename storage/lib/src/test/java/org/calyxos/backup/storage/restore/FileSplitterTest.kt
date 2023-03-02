/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FileSplitterTest {

    private val fileSplitter = FileSplitter

    init {
        mockLog()
    }

    @Test
    fun testDuplicateZipChunks() {
        val chunkId = getRandomString()
        val mediaFiles = listOf(
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("1a").setZipIndex(1).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("2a").setZipIndex(2).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("1b").setZipIndex(1).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("2b").setZipIndex(2).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("1c").setZipIndex(1).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("2c").setZipIndex(2).build(),
        )
        val snapshot = BackupSnapshot.newBuilder()
            .setVersion(Backup.VERSION.toInt())
            .addAllMediaFiles(mediaFiles)
            .build()
        val result = fileSplitter.splitSnapshot(snapshot)

        assertTrue(result.multiChunkFiles.isEmpty())
        assertTrue(result.multiChunkMap.isEmpty())
        assertTrue(result.singleChunks.isEmpty())

        assertEquals(1, result.zipChunks.size)
        val restorableChunk = result.zipChunks.iterator().next()
        assertEquals(2, restorableChunk.files.size)
        assertEquals("1a", restorableChunk.files[0].name)
        assertEquals("2a", restorableChunk.files[1].name)
    }

    @Test
    fun testDuplicateZipChunksWithGaps() {
        val chunkId = getRandomString()
        val mediaFiles = listOf(
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("3a").setZipIndex(7).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("1a").setZipIndex(2).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("2a").setZipIndex(6).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("4a").setZipIndex(9).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("1b").setZipIndex(2).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("2b").setZipIndex(6).build(),
            BackupMediaFile.newBuilder().addChunkIds(chunkId).setName("4b").setZipIndex(9).build(),
        )
        val snapshot = BackupSnapshot.newBuilder()
            .setVersion(Backup.VERSION.toInt())
            .addAllMediaFiles(mediaFiles)
            .build()
        val result = fileSplitter.splitSnapshot(snapshot)

        assertTrue(result.multiChunkFiles.isEmpty())
        assertTrue(result.multiChunkMap.isEmpty())
        assertTrue(result.singleChunks.isEmpty())

        assertEquals(1, result.zipChunks.size)
        val restorableChunk = result.zipChunks.iterator().next()
        assertEquals(4, restorableChunk.files.size)
        assertEquals("1a", restorableChunk.files[0].name)
        assertEquals("2a", restorableChunk.files[1].name)
        assertEquals("3a", restorableChunk.files[2].name)
        assertEquals("4a", restorableChunk.files[3].name)
    }

}
