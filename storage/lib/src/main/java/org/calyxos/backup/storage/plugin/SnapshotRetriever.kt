package org.calyxos.backup.storage.plugin

import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.restore.readVersion
import java.io.IOException
import java.security.GeneralSecurityException

@Suppress("BlockingMethodInNonBlockingContext")
internal class SnapshotRetriever(
    private val storagePlugin: StoragePlugin,
    private val streamCrypto: StreamCrypto = StreamCrypto,
) {

    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun getSnapshot(streamKey: ByteArray, timestamp: Long): BackupSnapshot {
        return storagePlugin.getBackupSnapshotInputStream(timestamp).use { inputStream ->
            val version = inputStream.readVersion()
            val ad = streamCrypto.getAssociatedDataForSnapshot(timestamp, version.toByte())
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                BackupSnapshot.parseFrom(decryptedStream)
            }
        }
    }

}
