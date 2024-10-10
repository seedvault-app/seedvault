/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.crypto

import org.calyxos.seedvault.core.crypto.CoreCrypto.ALGORITHM_HMAC
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.math.ceil
import kotlin.math.min

internal object Hkdf {

    /**
     * Step 2 of RFC 5869.
     *
     * Based on the Apache2 licensed HKDF library by Patrick Favre-Bulle.
     * Link: https://github.com/patrickfav/hkdf
     *
     * @param secretKey a pseudorandom key of at least hmac hash length in bytes
     * (usually, the output from the extract step)
     * @param info            optional context and application specific information; may be null
     * @param outLengthBytes  length of output keying material in bytes
     * (must be <= 255 * mac hash length)
     * @return new byte array of output keying material (OKM)
     */
    @Throws(GeneralSecurityException::class)
    internal fun expand(secretKey: SecretKey, info: ByteArray?, outLengthBytes: Int): ByteArray {
        require(outLengthBytes > 0) { "out length bytes must be at least 1" }

        val hmacHasher: Mac = Mac.getInstance(ALGORITHM_HMAC).apply {
            init(secretKey)
        }

        val iterations = ceil(outLengthBytes.toDouble() / hmacHasher.macLength).toInt()
        require(iterations <= 255) {
            "out length must be maximal 255 * hash-length; requested: $outLengthBytes bytes"
        }

        val buffer: ByteBuffer = ByteBuffer.allocate(outLengthBytes)
        var blockN = ByteArray(0)
        var remainingBytes = outLengthBytes
        var stepSize: Int
        for (i in 0 until iterations) {
            hmacHasher.update(blockN)
            hmacHasher.update(info ?: ByteArray(0))
            hmacHasher.update((i + 1).toByte())
            blockN = hmacHasher.doFinal()
            stepSize = min(remainingBytes, blockN.size)
            buffer.put(blockN, 0, stepSize)
            remainingBytes -= stepSize
        }
        return buffer.array()
    }

}
