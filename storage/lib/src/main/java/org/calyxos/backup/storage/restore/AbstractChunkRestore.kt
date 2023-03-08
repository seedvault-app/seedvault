/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
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
        version: Int,
        storedSnapshot: StoredSnapshot,
        chunkId: String,
        streamReader: suspend (InputStream) -> Unit,
    ) {
        storagePlugin.getChunkInputStream(storedSnapshot, chunkId).use { inputStream ->
            inputStream.readVersion(version)
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId, version.toByte())
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
        // TODO check if the file exists already (same name, size, chunk IDs)
        //  and skip it in this case
        fileRestore.restoreFile(file, observer, tag, streamWriter)
    }

}
