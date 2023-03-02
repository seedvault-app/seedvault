/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.SecretKey

public interface StoragePlugin {

    /**
     * Called before starting a backup run to ensure that all cached chunks are still available.
     * Plugins should use this opportunity
     * to ensure they are ready to store a large number of chunks.
     */
    @Throws(IOException::class)
    public suspend fun getAvailableChunkIds(): List<String>

    /**
     * Returns a [SecretKey] for HmacSHA256, ideally stored in the [KeyStore].
     */
    public fun getMasterKey(): SecretKey

    /**
     * Returns true if the key for [getMasterKey] exists, false otherwise.
     */
    public fun hasMasterKey(): Boolean

    @Throws(IOException::class)
    public fun getChunkOutputStream(chunkId: String): OutputStream

    @Throws(IOException::class)
    public fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream

    /* Restore */

    /**
     * Returns *all* [StoredSnapshot]s that are available on storage
     * independent of user ID and whether they can be decrypted
     * with the key returned by [getMasterKey].
     */
    @Throws(IOException::class)
    public suspend fun getBackupSnapshotsForRestore(): List<StoredSnapshot>

    @Throws(IOException::class)
    public suspend fun getBackupSnapshotInputStream(storedSnapshot: StoredSnapshot): InputStream

    @Throws(IOException::class)
    public suspend fun getChunkInputStream(snapshot: StoredSnapshot, chunkId: String): InputStream

    /* Pruning */

    /**
     * Returns [StoredSnapshot]s for the currently active user ID.
     */
    @Throws(IOException::class)
    public suspend fun getCurrentBackupSnapshots(): List<StoredSnapshot>

    /**
     * Deletes the given [StoredSnapshot].
     */
    @Throws(IOException::class)
    public suspend fun deleteBackupSnapshot(storedSnapshot: StoredSnapshot)

    /**
     * Deletes the given chunks of the *current* user ID only.
     */
    @Throws(IOException::class)
    public suspend fun deleteChunks(chunkIds: List<String>)

}
