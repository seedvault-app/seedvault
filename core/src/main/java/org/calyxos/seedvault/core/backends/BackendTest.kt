/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import androidx.annotation.VisibleForTesting
import at.bitfire.dav4jvm.exception.HttpException
import org.calyxos.seedvault.core.toHexString
import org.junit.Assert.assertArrayEquals
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.fail

@VisibleForTesting
public abstract class BackendTest {

    public abstract val plugin: Backend

    protected suspend fun testWriteListReadRenameDelete() {
        try {
            plugin.removeAll()
        } catch (e: HttpException) {
            if (e.code != 404) fail(e.message, e)
        }

        val now = System.currentTimeMillis()
        val bytes1 = Random.nextBytes(1337)
        val bytes2 = Random.nextBytes(1337 * 8)
        plugin.save(LegacyAppBackupFile.Metadata(now)).use {
            it.write(bytes1)
        }

        plugin.save(FileBackupFileType.Snapshot("0123456789abcdef", now)).use {
            it.write(bytes2)
        }

        var metadata: LegacyAppBackupFile.Metadata? = null
        var snapshot: FileBackupFileType.Snapshot? = null
        plugin.list(
            null,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { fileInfo ->
            val handle = fileInfo.fileHandle
            if (handle is LegacyAppBackupFile.Metadata && handle.token == now) {
                metadata = handle
            } else if (handle is FileBackupFileType.Snapshot && handle.time == now) {
                snapshot = handle
            }
        }
        assertNotNull(metadata)
        assertNotNull(snapshot)

        assertArrayEquals(bytes1, plugin.load(metadata as FileHandle).readAllBytes())
        assertArrayEquals(bytes2, plugin.load(snapshot as FileHandle).readAllBytes())

        val blobName = Random.nextBytes(32).toHexString()
        var blob: FileBackupFileType.Blob? = null
        val bytes3 = Random.nextBytes(1337 * 16)
        plugin.save(FileBackupFileType.Blob("0123456789abcdef", blobName)).use {
            it.write(bytes3)
        }
        plugin.list(
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
        assertArrayEquals(bytes3, plugin.load(blob as FileHandle).readAllBytes())

        // try listing with top-level folder, should find two files of FileBackupFileType in there
        var numFiles = 0
        plugin.list(
            snapshot!!.topLevelFolder,
            FileBackupFileType.Snapshot::class,
            FileBackupFileType.Blob::class,
            LegacyAppBackupFile.Metadata::class,
        ) { numFiles++ }
        assertEquals(2, numFiles)

        plugin.remove(snapshot as FileHandle)

        // rename snapshots
        val snapshotNewFolder = TopLevelFolder("0123456789abcdee.sv")
        plugin.rename(snapshot!!.topLevelFolder, snapshotNewFolder)

        // rename to existing folder should fail
        val e = assertFailsWith<Exception> {
            plugin.rename(snapshotNewFolder, metadata!!.topLevelFolder)
        }
        println(e)

        plugin.remove(metadata!!.topLevelFolder)
        plugin.remove(snapshotNewFolder)
    }

}
