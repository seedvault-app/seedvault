/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

public object CoreCrypto {

    private const val KEY_SIZE = 256
    private const val SIZE_SEGMENT = 1 shl 20 // 1024 * 1024
    public const val KEY_SIZE_BYTES: Int = KEY_SIZE / 8
    public const val ALGORITHM_HMAC: String = "HmacSHA256"

    @Throws(GeneralSecurityException::class)
    public fun deriveKey(mainKey: SecretKey, info: ByteArray): ByteArray = Hkdf.expand(
        secretKey = mainKey,
        info = info,
        outLengthBytes = KEY_SIZE_BYTES,
    )

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
            0,
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
            0,
        ).newDecryptingStream(inputStream, associatedData)
    }

}
