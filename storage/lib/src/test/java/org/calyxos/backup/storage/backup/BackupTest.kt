/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.text.format.Formatter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.backup.Backup.Companion.CHUNK_SIZE_MAX
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.db.FilesCache
import org.calyxos.backup.storage.getRandomDocFile
import org.calyxos.backup.storage.getRandomString
import org.calyxos.backup.storage.mockLog
import org.calyxos.backup.storage.scanner.FileScanner
import org.calyxos.backup.storage.scanner.FileScannerResult
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendSaver
import org.calyxos.seedvault.core.backends.FileBackupFileType.Blob
import org.calyxos.seedvault.core.backends.FileBackupFileType.Snapshot
import org.calyxos.seedvault.core.backends.IBackendManager
import org.calyxos.seedvault.core.crypto.CoreCrypto.ALGORITHM_HMAC
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import org.calyxos.seedvault.core.crypto.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal class BackupTest {

    private val context: Context = mockk()
    private val db: Db = mockk()

    private val fileScanner: FileScanner = mockk()
    private val backendManager: IBackendManager = mockk()
    private val androidId: String = getRandomString()
    private val keyManager: KeyManager = mockk()
    private val cacheRepopulater: ChunksCacheRepopulater = mockk()
    private val backend: Backend = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val filesCache: FilesCache = mockk()
    private val chunksCache: ChunksCache = mockk()

    init {
        mockLog()
        mockkStatic(Formatter::class)
        every { Formatter.formatShortFileSize(any(), any()) } returns ""

        every { context.contentResolver } returns contentResolver
        every { db.getFilesCache() } returns filesCache
        every { db.getChunksCache() } returns chunksCache
        every { keyManager.getMainKey() } returns SecretKeySpec(
            "This is a backup key for testing".toByteArray(),
            0, KEY_SIZE_BYTES, ALGORITHM_HMAC
        )
        every { context.checkSelfPermission(ACCESS_MEDIA_LOCATION) } returns PERMISSION_DENIED
        every { backendManager.backend } returns backend
    }

    private val backup = Backup(
        context = context,
        db = db,
        fileScanner = fileScanner,
        backendManager = backendManager,
        androidId = androidId,
        keyManager = keyManager,
        cacheRepopulater = cacheRepopulater,
    )

    @Test
    fun testRunBackupRetriesSaving() {
        // define one file in backup
        val fileMBytes = Random.nextBytes(Random.nextInt(1, CHUNK_SIZE_MAX))
        val fileM = getRandomDocFile(fileMBytes.size)
        val scannedFiles = FileScannerResult(
            smallFiles = emptyList(),
            files = listOf(fileM),
        )

        // preliminaries find the file above
        prepareBackup(scannedFiles)

        // backup file and save its blob
        every {
            contentResolver.openInputStream(fileM.uri)
        } returns ByteArrayInputStream(fileMBytes) andThen ByteArrayInputStream(fileMBytes)
        coEvery { backend.save(match { it is Blob }, any()) } returns 42L

        // save snapshot (the interesting part!)
        val saverSlot = slot<BackendSaver>()
        val outputStream1 = ByteArrayOutputStream()
        val outputStream2 = ByteArrayOutputStream()
        val outputStream3 = ByteArrayOutputStream()
        var size1 = -1L
        var size2 = -1L
        coEvery { backend.save(match { it is Snapshot }, capture(saverSlot)) } answers {
            // saver saves 3 times
            size1 = saverSlot.captured.save(outputStream1)
            size2 = saverSlot.captured.save(outputStream2)
            saverSlot.captured.save(outputStream3)
        }

        // post snapshot work
        every { chunksCache.insert(any<CachedChunk>()) } just Runs
        every { filesCache.upsert(any()) } just Runs
        every { db.applyInParts<String>(any(), any()) } just Runs

        runBlocking {
            backup.runBackup(null)
        }

        // output is always the same, no matter how much often we save
        val bytes = outputStream1.toByteArray()
        assertTrue(bytes.isNotEmpty())
        assertArrayEquals(bytes, outputStream2.toByteArray())
        assertArrayEquals(bytes, outputStream3.toByteArray())

        assertEquals(size1, outputStream1.size().toLong())
        assertEquals(size2, outputStream2.size().toLong())
    }

    @Test
    fun testAbortBackupEarly() {
        every { backendManager.canDoBackupNow() } returns false

        val e = assertThrows(IOException::class.java) {
            runBlocking {
                backup.runBackup(null)
            }
        }
        assertEquals("Metered Network", e.message)
    }

    @Test
    fun testAbortBackupBeforeSmallFiles() {
        // define one file in backup
        val fileMBytes = Random.nextBytes(Random.nextInt(1, CHUNK_SIZE_MAX))
        val fileM = getRandomDocFile(fileMBytes.size)
        val scannedFiles = FileScannerResult(
            smallFiles = listOf(fileM),
            files = listOf(fileM),
        )

        prepareBackup(scannedFiles)
        every { backendManager.canDoBackupNow() } returns true andThen false
        every {
            contentResolver.openInputStream(fileM.uri)
        } returns ByteArrayInputStream(fileMBytes)

        val e = assertThrows(IOException::class.java) {
            runBlocking {
                backup.runBackup(null)
            }
        }
        assertEquals("Metered Network", e.message)
    }

    @Test
    fun testAbortBackupBeforeLargeFiles() {
        // define one file in backup
        val fileMBytes = Random.nextBytes(Random.nextInt(1, CHUNK_SIZE_MAX))
        val fileM = getRandomDocFile(fileMBytes.size)
        val scannedFiles = FileScannerResult(
            smallFiles = listOf(fileM),
            files = listOf(fileM),
        )

        prepareBackup(scannedFiles)
        every { backendManager.canDoBackupNow() } returnsMany listOf(true, true, true, false)
        every {
            contentResolver.openInputStream(fileM.uri)
        } returns ByteArrayInputStream(fileMBytes)
        coEvery { backend.save(match { it is Blob }, any()) } returns 42L
        every { chunksCache.insert(any<CachedChunk>()) } just Runs
        every { filesCache.upsert(any()) } just Runs

        val e = assertThrows(IOException::class.java) {
            runBlocking {
                backup.runBackup(null)
            }
        }
        assertEquals("Metered Network", e.message)

        verify {
            filesCache.upsert(any()) // small file got backed up
        }
    }

    private fun prepareBackup(scannedFiles: FileScannerResult) {
        every { backendManager.canDoBackupNow() } returns true
        coEvery { backend.list(any(), Blob::class, callback = any()) } just Runs
        every { chunksCache.areAllAvailableChunksCached(emptySet()) } returns true
        every { fileScanner.getFiles() } returns scannedFiles
        every { filesCache.getByUri(any()) } returns null // nothing is cached, all is new
        every { chunksCache.get(any()) } returns null // no chunks are cached, all are new
        every { chunksCache.hasCorruptedChunks(any()) } returns false // no chunks corrupted
    }
}
