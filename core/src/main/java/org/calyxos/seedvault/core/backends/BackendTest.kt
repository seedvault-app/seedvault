/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import androidx.annotation.VisibleForTesting
import org.calyxos.seedvault.core.toHexString
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@VisibleForTesting
public abstract class BackendTest {

    public abstract val backend: Backend

    protected suspend fun testWriteListReadRenameDelete() {
        backend.removeAll()

        val androidId = "0123456789abcdef"
        val now = System.currentTimeMillis()
        val bytes1 = Random.nextBytes(1337)
        val bytes2 = Random.nextBytes(1337 * 8)
        backend.save(LegacyAppBackupFile.Metadata(now)).use {
            it.write(bytes1)
        }

        backend.save(FileBackupFileType.Snapshot(androidId, now)).use {
            it.write(bytes2)
        }

        var metadata: LegacyAppBackupFile.Metadata? = null
        var fileSnapshot: FileBackupFileType.Snapshot? = null
        backend.list(
            null,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { fileInfo ->
            val handle = fileInfo.fileHandle
            if (handle is LegacyAppBackupFile.Metadata && handle.token == now) {
                metadata = handle
            } else if (handle is FileBackupFileType.Snapshot && handle.time == now) {
                fileSnapshot = handle
            }
        }
        assertNotNull(metadata)
        assertNotNull(fileSnapshot)

        assertContentEquals(bytes1, backend.load(metadata as FileHandle).readAllBytes())
        assertContentEquals(bytes2, backend.load(fileSnapshot as FileHandle).readAllBytes())

        val blobName = Random.nextBytes(32).toHexString()
        var blob: FileBackupFileType.Blob? = null
        val bytes3 = Random.nextBytes(1337 * 16)
        backend.save(FileBackupFileType.Blob(androidId, blobName)).use {
            it.write(bytes3)
        }
        backend.list(
            null,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { fileInfo ->
            val handle = fileInfo.fileHandle
            if (handle is FileBackupFileType.Blob && handle.name == blobName) {
                blob = handle
            }
        }
        assertNotNull(blob)
        assertContentEquals(bytes3, backend.load(blob as FileHandle).readAllBytes())

        // try listing with top-level folder, should find two files of FileBackupFileType in there
        var numFiles = 0
        backend.list(
            fileSnapshot!!.topLevelFolder,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { numFiles++ }
        assertEquals(2, numFiles)

        val repoId = Random.nextBytes(32).toHexString()
        val snapshotId = Random.nextBytes(32).toHexString()
        val blobId = Random.nextBytes(32).toHexString()

        val bytes4 = Random.nextBytes(1337)
        val bytes5 = Random.nextBytes(1337 * 8)
        backend.save(AppBackupFileType.Snapshot(repoId, snapshotId)).use {
            it.write(bytes4)
        }

        var appSnapshot: AppBackupFileType.Snapshot? = null
        backend.list(
            null,
            AppBackupFileType.Snapshot::class,
        ) { fileInfo ->
            val handle = fileInfo.fileHandle
            if (handle is AppBackupFileType.Snapshot) {
                appSnapshot = handle
            }
        }
        assertNotNull(appSnapshot)
        assertContentEquals(bytes4, backend.load(appSnapshot as FileHandle).readAllBytes())

        backend.save(AppBackupFileType.Blob(repoId, blobId)).use {
            it.write(bytes5)
        }

        var blobHandle: AppBackupFileType.Blob? = null
        backend.list(
            TopLevelFolder(repoId),
            AppBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { fileInfo ->
            val handle = fileInfo.fileHandle
            if (handle is AppBackupFileType.Blob) {
                blobHandle = handle
            }
        }
        assertNotNull(blobHandle)
        assertContentEquals(bytes5, backend.load(blobHandle as FileHandle).readAllBytes())

        backend.remove(fileSnapshot as FileHandle)
        backend.remove(appSnapshot as FileHandle)
        backend.remove(blobHandle as FileHandle)

        // rename snapshots
        val snapshotNewFolder = TopLevelFolder("a123456789abcdef.sv")
        backend.rename(fileSnapshot!!.topLevelFolder, snapshotNewFolder)

        // rename to existing folder should fail
        val e = assertFailsWith<Exception> {
            backend.rename(snapshotNewFolder, metadata!!.topLevelFolder)
        }
        println(e)

        backend.remove(metadata!!.topLevelFolder)
        backend.remove(snapshotNewFolder)
    }

    protected suspend fun testRemoveCreateWriteFile() {
        val now = System.currentTimeMillis()
        val blob = LegacyAppBackupFile.Blob(now, Random.nextBytes(32).toHexString())
        val bytes = Random.nextBytes(2342)

        backend.remove(blob)
        try {
            backend.save(blob).use {
                it.write(bytes)
            }
            assertContentEquals(bytes, backend.load(blob as FileHandle).readAllBytes())
        } finally {
            backend.remove(blob)
        }
    }

}
