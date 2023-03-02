/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.calyxos.backup.storage.backup.Backup.Companion.VERSION
import org.calyxos.backup.storage.crypto.Hkdf.ALGORITHM_HMAC
import org.calyxos.backup.storage.crypto.Hkdf.KEY_SIZE_BYTES
import org.calyxos.backup.storage.toByteArrayFromHex
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

public object StreamCrypto {

    private const val INFO_STREAM_KEY = "stream key"
    private const val SIZE_SEGMENT = 1 shl 20 // 1024 * 1024
    private const val TYPE_CHUNK: Byte = 0x00
    private const val TYPE_SNAPSHOT: Byte = 0x01

    @Throws(GeneralSecurityException::class)
    public fun deriveStreamKey(
        masterKey: SecretKey,
        info: ByteArray = INFO_STREAM_KEY.toByteArray(),
    ): ByteArray = Hkdf.expand(
        secretKey = masterKey,
        info = info,
        outLengthBytes = KEY_SIZE_BYTES
    )

    internal fun getAssociatedDataForChunk(chunkId: String, version: Byte = VERSION): ByteArray =
        ByteBuffer.allocate(2 + KEY_SIZE_BYTES)
            .put(version)
            .put(TYPE_CHUNK) // type ID for chunks
            .put(chunkId.toByteArrayFromHex().apply { check(size == KEY_SIZE_BYTES) })
            .array()

    internal fun getAssociatedDataForSnapshot(timestamp: Long, version: Byte = VERSION): ByteArray =
        ByteBuffer.allocate(2 + 8)
            .put(version)
            .put(TYPE_SNAPSHOT) // type ID for chunks
            .put(timestamp.toByteArray())
            .array()

    /**
     * Returns a [AesGcmHkdfStreaming] encrypting stream
     * that gets encrypted with the given secret.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    public fun newEncryptingStream(
        secret: ByteArray,
        outputStream: OutputStream,
        associatedData: ByteArray = ByteArray(0),
    ): OutputStream {
        return AesGcmHkdfStreaming(
            secret,
            ALGORITHM_HMAC,
            KEY_SIZE_BYTES,
            SIZE_SEGMENT,
            0
        ).newEncryptingStream(outputStream, associatedData)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    public fun newDecryptingStream(
        secret: ByteArray,
        inputStream: InputStream,
        associatedData: ByteArray = ByteArray(0),
    ): InputStream {
        return AesGcmHkdfStreaming(
            secret,
            ALGORITHM_HMAC,
            KEY_SIZE_BYTES,
            SIZE_SEGMENT,
            0
        ).newDecryptingStream(inputStream, associatedData)
    }

    public fun Long.toByteArray(): ByteArray = ByteArray(8).apply {
        var l = this@toByteArray
        for (i in 7 downTo 0) {
            this[i] = (l and 0xFF).toByte()
            l = l shr 8
        }
    }

}
