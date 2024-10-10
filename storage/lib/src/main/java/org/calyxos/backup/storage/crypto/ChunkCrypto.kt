/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.crypto

import org.calyxos.backup.storage.backup.Chunker
import org.calyxos.seedvault.core.crypto.CoreCrypto
import org.calyxos.seedvault.core.crypto.CoreCrypto.ALGORITHM_HMAC
import org.calyxos.seedvault.core.crypto.CoreCrypto.KEY_SIZE_BYTES
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal object ChunkCrypto {

    private const val INFO_CHUNK_ID = "Chunk ID calculation"

    /**
     * We are deriving a dedicated key for chunk ID derivation,
     * because using a [ByteArray] instead of a key inside the [KeyStore]
     * is orders of magnitude faster.
     */
    @Throws(GeneralSecurityException::class)
    fun deriveChunkIdKey(
        mainKey: SecretKey,
        info: ByteArray = INFO_CHUNK_ID.toByteArray(),
    ): ByteArray = CoreCrypto.deriveKey(
        mainKey = mainKey,
        info = info,
    )

    /**
     * Gets a re-usable [Mac] instance to be used with [Chunker.makeChunks].
     */
    @Throws(GeneralSecurityException::class)
    fun getMac(chunkKey: ByteArray): Mac = Mac.getInstance(ALGORITHM_HMAC).apply {
        check(chunkKey.size == KEY_SIZE_BYTES) {
            "Chunk key has ${chunkKey.size} bytes, but $KEY_SIZE_BYTES expected."
        }
        init(SecretKeySpec(chunkKey, ALGORITHM_HMAC))
    }

}
