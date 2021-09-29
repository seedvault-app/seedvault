package org.calyxos.backup.storage.plugin

import com.google.protobuf.InvalidProtocolBufferException
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
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

    @Throws(
        IOException::class,
        GeneralSecurityException::class,
        InvalidProtocolBufferException::class,
    )
    suspend fun getSnapshot(streamKey: ByteArray, storedSnapshot: StoredSnapshot): BackupSnapshot {
        return storagePlugin.getBackupSnapshotInputStream(storedSnapshot).use { inputStream ->
            val version = inputStream.readVersion()
            val timestamp = storedSnapshot.timestamp
            val ad = streamCrypto.getAssociatedDataForSnapshot(timestamp, version.toByte())
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                BackupSnapshot.parseFrom(decryptedStream)
            }
        }
    }

}
