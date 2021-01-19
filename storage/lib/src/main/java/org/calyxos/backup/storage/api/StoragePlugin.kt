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
     * Returns the timestamps representing a backup snapshot that are available on storage.
     */
    @Throws(IOException::class)
    public suspend fun getAvailableBackupSnapshots(): List<Long>

    @Throws(IOException::class)
    public suspend fun getBackupSnapshotInputStream(timestamp: Long): InputStream

    @Throws(IOException::class)
    public suspend fun getChunkInputStream(chunkId: String): InputStream

    /* Pruning */

    @Throws(IOException::class)
    public suspend fun deleteBackupSnapshot(timestamp: Long)

    @Throws(IOException::class)
    public suspend fun deleteChunks(chunkIds: List<String>)

}
