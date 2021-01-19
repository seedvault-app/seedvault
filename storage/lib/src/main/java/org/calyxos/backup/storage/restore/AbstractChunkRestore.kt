package org.calyxos.backup.storage.restore

import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.crypto.StreamCrypto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

@Suppress("BlockingMethodInNonBlockingContext")
internal abstract class AbstractChunkRestore(
    private val storagePlugin: StoragePlugin,
    private val fileRestore: FileRestore,
    private val streamCrypto: StreamCrypto,
    private val streamKey: ByteArray,
) {

    @Throws(IOException::class, GeneralSecurityException::class)
    protected suspend fun getAndDecryptChunk(
        chunkId: String,
        streamReader: suspend (InputStream) -> Unit,
    ) {
        storagePlugin.getChunkInputStream(chunkId).use { inputStream ->
            inputStream.readVersion()
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId)
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                streamReader(decryptedStream)
            }
        }
    }

    @Throws(IOException::class)
    protected suspend fun restoreFile(
        file: RestorableFile,
        observer: RestoreObserver?,
        tag: String,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ) {
        fileRestore.restoreFile(file, observer, tag, streamWriter)
    }

}
