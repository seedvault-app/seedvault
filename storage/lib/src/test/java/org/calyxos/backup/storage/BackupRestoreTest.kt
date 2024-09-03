/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.provider.MediaStore
import android.text.format.Formatter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.calyxos.backup.storage.api.SnapshotResult
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.Backup.Companion.CHUNK_SIZE_MAX
import org.calyxos.backup.storage.backup.Backup.Companion.SMALL_FILE_SIZE_MAX
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.backup.ChunksCacheRepopulater
import org.calyxos.backup.storage.content.ContentFile
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.content.MediaFile
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.CachedFile
import org.calyxos.backup.storage.db.ChunksCache
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.db.FilesCache
import org.calyxos.backup.storage.restore.FileRestore
import org.calyxos.backup.storage.restore.RestorableFile
import org.calyxos.backup.storage.restore.Restore
import org.calyxos.backup.storage.scanner.FileScanner
import org.calyxos.backup.storage.scanner.FileScannerResult
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType.Blob
import org.calyxos.seedvault.core.backends.FileBackupFileType.Snapshot
import org.calyxos.seedvault.core.crypto.CoreCrypto.ALGORITHM_HMAC
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import org.calyxos.seedvault.core.crypto.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal class BackupRestoreTest {

    @get:Rule
    var folder: TemporaryFolder = TemporaryFolder()

    private val context: Context = mockk()
    private val db: Db = mockk()
    private val filesCache: FilesCache = mockk()
    private val chunksCache: ChunksCache = mockk()
    private val contentResolver: ContentResolver = mockk()

    private val fileScanner: FileScanner = mockk()
    private val backendGetter: () -> Backend = mockk()
    private val androidId: String = getRandomString()
    private val keyManager: KeyManager = mockk()
    private val backend: Backend = mockk()
    private val fileRestore: FileRestore = mockk()
    private val snapshotRetriever = SnapshotRetriever(backendGetter)
    private val cacheRepopulater: ChunksCacheRepopulater = mockk()

    init {
        mockLog()
        mockkStatic("org.calyxos.backup.storage.SnapshotRetrieverKt")
        mockkStatic(Formatter::class)
        every { Formatter.formatShortFileSize(any(), any()) } returns ""

        mockkStatic("org.calyxos.backup.storage.UriUtilsKt")

        every { backendGetter() } returns backend
        every { db.getFilesCache() } returns filesCache
        every { db.getChunksCache() } returns chunksCache
        every { keyManager.getMainKey() } returns SecretKeySpec(
            "This is a backup key for testing".toByteArray(),
            0, KEY_SIZE_BYTES, ALGORITHM_HMAC
        )
        every { context.checkSelfPermission(ACCESS_MEDIA_LOCATION) } returns PERMISSION_DENIED
        every { context.contentResolver } returns contentResolver
    }

    private val restore =
        Restore(context, backendGetter, keyManager, snapshotRetriever, fileRestore)

    @Test
    fun testZipAndSingleRandom(): Unit = runBlocking {
        val backup =
            Backup(context, db, fileScanner, backendGetter, androidId, keyManager, cacheRepopulater)

        val smallFileMBytes = Random.nextBytes(Random.nextInt(SMALL_FILE_SIZE_MAX))
        val smallFileM = getRandomMediaFile(smallFileMBytes.size)
        val smallFileDBytes = Random.nextBytes(Random.nextInt(SMALL_FILE_SIZE_MAX))
        val smallFileD = getRandomDocFile(smallFileDBytes.size)
        val fileMBytes = Random.nextBytes(Random.nextInt(SMALL_FILE_SIZE_MAX + 1, CHUNK_SIZE_MAX))
        val fileM = getRandomMediaFile(fileMBytes.size)
        val fileDBytes = Random.nextBytes(Random.nextInt(SMALL_FILE_SIZE_MAX + 1, CHUNK_SIZE_MAX))
        val fileD = getRandomDocFile(fileDBytes.size)
        val scannedFiles = FileScannerResult(
            smallFiles = listOf(smallFileM, smallFileD),
            files = listOf(fileM, fileD),
        )
        val cachedFiles = mutableListOf<CachedFile>()
        val zipChunkOutputStream = ByteArrayOutputStream()
        val mOutputStream = ByteArrayOutputStream()
        val dOutputStream = ByteArrayOutputStream()
        val snapshotHandle = slot<Snapshot>()
        val snapshotOutputStream = ByteArrayOutputStream()

        // provide files and empty cache
        val availableChunks = emptyList<String>()
        coEvery { backend.list(any(), Blob::class, callback = any()) } just Runs
        every {
            chunksCache.areAllAvailableChunksCached(db, availableChunks.toHashSet())
        } returns true
        every { fileScanner.getFiles() } returns scannedFiles
        every { filesCache.getByUri(any()) } returns null // nothing is cached, all is new
        every { chunksCache.get(any()) } returns null // no chunks are cached, all are new

        // media types
        every { smallFileM.uri.getBackupMediaType() } returns BackupMediaFile.MediaType.IMAGES
        every { fileM.uri.getBackupMediaType() } returns BackupMediaFile.MediaType.DOWNLOADS

        // file input streams
        every {
            contentResolver.openInputStream(smallFileM.uri)
        } returns ByteArrayInputStream(smallFileMBytes)
        every {
            contentResolver.openInputStream(smallFileD.uri)
        } returns ByteArrayInputStream(smallFileDBytes)
        // don't return the same stream twice here, as we don't reset it, but read it twice
        every {
            contentResolver.openInputStream(fileM.uri)
        } returns ByteArrayInputStream(fileMBytes) andThen ByteArrayInputStream(fileMBytes)
        every {
            contentResolver.openInputStream(fileD.uri)
        } returns ByteArrayInputStream(fileDBytes) andThen ByteArrayInputStream(fileDBytes)

        // output streams and caching
        coEvery { backend.save(any<Blob>()) } returnsMany listOf(
            zipChunkOutputStream, mOutputStream, dOutputStream
        )
        every { chunksCache.insert(any<CachedChunk>()) } just Runs
        every { filesCache.upsert(capture(cachedFiles)) } just Runs

        // snapshot writing
        coEvery { backend.save(capture(snapshotHandle)) } returns snapshotOutputStream
        every { db.applyInParts<String>(any(), any()) } just Runs

        backup.runBackup(null)

        // assert file cache info
        assertEquals(4, cachedFiles.size)
        assertEquals(smallFileM.size, cachedFiles[0].size)
        assertEquals(smallFileM.lastModified, cachedFiles[0].lastModified)
        assertEquals(1, cachedFiles[0].zipIndex)
        assertEquals(smallFileD.size, cachedFiles[1].size)
        assertEquals(smallFileD.lastModified, cachedFiles[1].lastModified)
        assertEquals(2, cachedFiles[1].zipIndex)
        assertEquals(fileM.size, cachedFiles[2].size)
        assertEquals(fileM.lastModified, cachedFiles[2].lastModified)
        assertEquals(fileD.size, cachedFiles[3].size)
        assertEquals(fileD.lastModified, cachedFiles[3].lastModified)

        // RESTORE

        val storedSnapshot = StoredSnapshot("$androidId.sv", snapshotHandle.captured.time)

        val smallFileMOutputStream = ByteArrayOutputStream()
        val smallFileDOutputStream = ByteArrayOutputStream()
        val fileMOutputStream = ByteArrayOutputStream()
        val fileDOutputStream = ByteArrayOutputStream()

        coEvery { backend.getBackupSnapshotsForRestore() } returns listOf(storedSnapshot)
        coEvery {
            backend.load(storedSnapshot.snapshotHandle)
        } returns ByteArrayInputStream(snapshotOutputStream.toByteArray())

        // retrieve snapshots
        val snapshotResultList = restore.getBackupSnapshots().toList()

        // assert snapshot
        assertEquals(2, snapshotResultList.size)
        val snapshots = (snapshotResultList[1] as SnapshotResult.Success).snapshots
        assertEquals(1, snapshots.size)
        assertEquals(snapshotHandle.captured.time, snapshots[0].time)
        val snapshot = snapshots[0].snapshot ?: error("snapshot was null")
        assertEquals(2, snapshot.mediaFilesList.size)
        assertEquals(2, snapshot.documentFilesList.size)

        // pipe chunks back in
        coEvery {
            backend.load(Blob(androidId, cachedFiles[0].chunks[0]))
        } returns ByteArrayInputStream(zipChunkOutputStream.toByteArray())
        // cachedFiles[0].chunks[1] is in previous zipChunk
        coEvery {
            backend.load(Blob(androidId, cachedFiles[2].chunks[0]))
        } returns ByteArrayInputStream(mOutputStream.toByteArray())
        coEvery {
            backend.load(Blob(androidId, cachedFiles[3].chunks[0]))
        } returns ByteArrayInputStream(dOutputStream.toByteArray())

        // provide file output streams for restore
        val smallFileMRestorable = getRestorableFileM(smallFileM, snapshot)
        expectRestoreFile(smallFileMRestorable, smallFileMOutputStream)
        val smallFileDRestorable = getRestorableFileD(smallFileD, snapshot)
        expectRestoreFile(smallFileDRestorable, smallFileDOutputStream)
        val fileMRestorable = getRestorableFileM(fileM, snapshot)
        expectRestoreFile(fileMRestorable, fileMOutputStream)
        val fileDRestorable = getRestorableFileD(fileD, snapshot)
        expectRestoreFile(fileDRestorable, fileDOutputStream)

        restore.restoreBackupSnapshot(storedSnapshot, snapshot, null)

        // restored files match backed up files exactly
        assertArrayEquals(smallFileMBytes, smallFileMOutputStream.toByteArray())
        assertArrayEquals(smallFileDBytes, smallFileDOutputStream.toByteArray())
        assertArrayEquals(fileMBytes, fileMOutputStream.toByteArray())
        assertArrayEquals(fileDBytes, fileDOutputStream.toByteArray())
    }

    @Test
    fun testMultiChunks(): Unit = runBlocking {
        val backup = Backup(
            context = context,
            db = db,
            fileScanner = fileScanner,
            backendGetter = backendGetter,
            androidId = androidId,
            keyManager = keyManager,
            cacheRepopulater = cacheRepopulater,
            chunkSizeMax = 4,
        )

        val chunk1 = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val chunk2 = byteArrayOf(0x04, 0x05, 0x06, 0x07)
        val chunk3 = byteArrayOf(0x08, 0x09, 0x10, 0x11)
        val chunk4 = byteArrayOf(0x12, 0x13)
        val file1Bytes = chunk1 + chunk2 + chunk3
        val file2Bytes = chunk3 + chunk2 + chunk1 + chunk4
        val file1 = getRandomDocFile(file1Bytes.size)
        val file2 = getRandomDocFile(file2Bytes.size)
        val file1OutputStream = ByteArrayOutputStream()
        val file2OutputStream = ByteArrayOutputStream()
        val snapshotHandle = slot<Snapshot>()
        val snapshotOutputStream = ByteArrayOutputStream()

        val scannedFiles = FileScannerResult(
            smallFiles = emptyList(),
            files = listOf(file1, file2),
        )
        val cachedFiles = mutableListOf<CachedFile>()

        // provide files and empty cache
        val availableChunks = emptyList<String>()
        coEvery { backend.list(any(), Blob::class, callback = any()) } just Runs
        every {
            chunksCache.areAllAvailableChunksCached(db, availableChunks.toHashSet())
        } returns true
        every { fileScanner.getFiles() } returns scannedFiles
        every { filesCache.getByUri(any()) } returns null // nothing is cached, all is new

        // first 3 chunks are not cached on 1st invocation, but afterwards. Last chunk never cached
        // also needed to ensure that we don't write chunks more than once into the same stream
        expectCacheMissAndThenHit(
            "040f3204869543c4015d92c04bf875b25ebde55f9645380f4172aa439b2825d3"
        )
        expectCacheMissAndThenHit(
            "901fbcf9a94271fc0455d0052522cab994f9392d0bb85187860282b4beadfb29"
        )
        expectCacheMissAndThenHit(
            "5adea3149fe6cf9c6e3270a52ee2c31bc9dfcef5f2080b583a4dd3b779c9182d"
        )
        every {
            chunksCache.get("40d00c1be4b0f89e8b12d47f3658aa42f568a8d02b978260da6d0050e7007e67")
        } returns null

        // file input streams
        // don't return the same stream twice here, as we don't reset it, but read it twice
        every {
            contentResolver.openInputStream(file1.uri)
        } returns ByteArrayInputStream(file1Bytes) andThen ByteArrayInputStream(file1Bytes)
        every {
            contentResolver.openInputStream(file2.uri)
        } returns ByteArrayInputStream(file2Bytes) andThen ByteArrayInputStream(file2Bytes)

        // use temporary directory as cache dir
        val tmpDir = folder.newFolder()
        every { context.cacheDir } returns tmpDir

        // output streams for deterministic chunks
        val id040f32 = ByteArrayOutputStream()
        coEvery {
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "040f3204869543c4015d92c04bf875b25ebde55f9645380f4172aa439b2825d3",
                )
            )
        } returns id040f32
        val id901fbc = ByteArrayOutputStream()
        coEvery {
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "901fbcf9a94271fc0455d0052522cab994f9392d0bb85187860282b4beadfb29",
                )
            )
        } returns id901fbc
        val id5adea3 = ByteArrayOutputStream()
        coEvery {
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "5adea3149fe6cf9c6e3270a52ee2c31bc9dfcef5f2080b583a4dd3b779c9182d",
                )
            )
        } returns id5adea3
        val id40d00c = ByteArrayOutputStream()
        coEvery {
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "40d00c1be4b0f89e8b12d47f3658aa42f568a8d02b978260da6d0050e7007e67",
                )
            )
        } returns id40d00c

        every { chunksCache.insert(any<CachedChunk>()) } just Runs
        every { filesCache.upsert(capture(cachedFiles)) } just Runs

        // snapshot writing
        coEvery { backend.save(capture(snapshotHandle)) } returns snapshotOutputStream
        every { db.applyInParts<String>(any(), any()) } just Runs

        backup.runBackup(null)

        // chunks were only written to storage once
        coVerify(exactly = 1) {
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "040f3204869543c4015d92c04bf875b25ebde55f9645380f4172aa439b2825d3",
                )
            )
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "901fbcf9a94271fc0455d0052522cab994f9392d0bb85187860282b4beadfb29",
                )
            )
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "5adea3149fe6cf9c6e3270a52ee2c31bc9dfcef5f2080b583a4dd3b779c9182d",
                )
            )
            backend.save(
                Blob(
                    androidId = androidId,
                    name = "40d00c1be4b0f89e8b12d47f3658aa42f568a8d02b978260da6d0050e7007e67",
                )
            )
        }

        // RESTORE

        val storedSnapshot = StoredSnapshot("$androidId.sv", snapshotHandle.captured.time)

        coEvery { backend.getBackupSnapshotsForRestore() } returns listOf(storedSnapshot)
        coEvery {
            backend.load(storedSnapshot.snapshotHandle)
        } returns ByteArrayInputStream(snapshotOutputStream.toByteArray())

        // retrieve snapshots
        val snapshotResultList = restore.getBackupSnapshots().toList()

        // assert snapshot
        val snapshots = (snapshotResultList[1] as SnapshotResult.Success).snapshots
        val snapshot = snapshots[0].snapshot ?: error("snapshot was null")
        assertEquals(0, snapshot.mediaFilesList.size)
        assertEquals(2, snapshot.documentFilesList.size)

        // pipe chunks back in
        coEvery {
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "040f3204869543c4015d92c04bf875b25ebde55f9645380f4172aa439b2825d3",
                )
            )
        } returns ByteArrayInputStream(id040f32.toByteArray())
        coEvery {
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "901fbcf9a94271fc0455d0052522cab994f9392d0bb85187860282b4beadfb29",
                )
            )
        } returns ByteArrayInputStream(id901fbc.toByteArray())
        coEvery {
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "5adea3149fe6cf9c6e3270a52ee2c31bc9dfcef5f2080b583a4dd3b779c9182d",
                )
            )
        } returns ByteArrayInputStream(id5adea3.toByteArray())
        coEvery {
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "40d00c1be4b0f89e8b12d47f3658aa42f568a8d02b978260da6d0050e7007e67",
                )
            )
        } returns ByteArrayInputStream(id40d00c.toByteArray())

        // provide file output streams for restore
        val file1Restorable = getRestorableFileD(file1, snapshot)
        expectRestoreFile(file1Restorable, file1OutputStream)
        val file2Restorable = getRestorableFileD(file2, snapshot)
        expectRestoreFile(file2Restorable, file2OutputStream)

        restore.restoreBackupSnapshot(storedSnapshot, snapshot, null)

        // restored files match backed up files exactly
        assertArrayEquals(file1Bytes, file1OutputStream.toByteArray())
        assertArrayEquals(file2Bytes, file2OutputStream.toByteArray())

        // chunks were only read from storage once
        coVerify(exactly = 1) {
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "040f3204869543c4015d92c04bf875b25ebde55f9645380f4172aa439b2825d3",
                )
            )
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "901fbcf9a94271fc0455d0052522cab994f9392d0bb85187860282b4beadfb29",
                )
            )
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "5adea3149fe6cf9c6e3270a52ee2c31bc9dfcef5f2080b583a4dd3b779c9182d",
                )
            )
            backend.load(
                Blob(
                    androidId = androidId,
                    name = "40d00c1be4b0f89e8b12d47f3658aa42f568a8d02b978260da6d0050e7007e67",
                )
            )
        }
    }

    @Test
    fun testBackupUpdatesBackend(): Unit = runBlocking {
        val backendGetterNew: () -> Backend = mockk()
        val backend1: Backend = mockk()
        val backend2: Backend = mockk()
        val backup = Backup(
            context = context,
            db = db,
            fileScanner = fileScanner,
            backendGetter = backendGetterNew,
            androidId = androidId,
            keyManager = keyManager,
            cacheRepopulater = cacheRepopulater,
        )
        every { backendGetterNew() } returnsMany listOf(backend1, backend2)

        coEvery { backend1.list(any(), Blob::class, callback = any()) } just Runs
        every { chunksCache.areAllAvailableChunksCached(db, emptySet()) } returns true
        every { fileScanner.getFiles() } returns FileScannerResult(emptyList(), emptyList())
        every { filesCache.getByUri(any()) } returns null // nothing is cached, all is new

        backup.runBackup(null)

        // second run uses new backend
        coEvery { backend2.list(any(), Blob::class, callback = any()) } just Runs
        backup.runBackup(null)

        coVerifyOrder {
            backend1.list(any(), Blob::class, callback = any())
            backend2.list(any(), Blob::class, callback = any())
        }
    }

    private fun getRandomMediaFile(size: Int) = MediaFile(
        uri = mockk(),
        dir = getRandomString(),
        fileName = getRandomString(),
        dateModified = Random.nextLong(),
        generationModified = Random.nextLong(),
        size = size.toLong(),
        isFavorite = Random.nextBoolean(),
        ownerPackageName = getRandomString(),
        volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    )

    private fun getRandomDocFile(size: Int) = DocFile(
        uri = mockk(),
        dirPath = getRandomString(),
        fileName = getRandomString(),
        lastModified = Random.nextLong(),
        size = size.toLong(),
        volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    )

    private fun getRestorableFileM(file: ContentFile, snapshot: BackupSnapshot): RestorableFile {
        return RestorableFile(snapshot.mediaFilesList.find {
            file.size == it.size
        } ?: error("not found"))
    }

    private fun getRestorableFileD(file: ContentFile, snapshot: BackupSnapshot): RestorableFile {
        return RestorableFile(snapshot.documentFilesList.find {
            file.size == it.size
        } ?: error("not found"))
    }

    private fun expectRestoreFile(restorableFile: RestorableFile, outputStream: OutputStream) {
        coEvery {
            fileRestore.restoreFile(restorableFile, null, any(), captureLambda())
        } coAnswers {
            val l = lambda<suspend (outputStream: OutputStream) -> Long>()
            assertTrue(l.isCaptured)
            l.captured.invoke(outputStream)
        }
    }

    private fun expectCacheMissAndThenHit(chunkId: String, chunkSize: Long = 4) {
        every {
            chunksCache.get(chunkId)
        } returns null andThen CachedChunk(chunkId, 0, chunkSize)
    }

}
