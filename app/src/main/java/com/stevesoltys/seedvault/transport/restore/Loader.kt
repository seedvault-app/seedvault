/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import com.github.luben.zstd.ZstdInputStream
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.GeneralSecurityException
import java.util.Enumeration

internal class Loader(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
) {

    /**
     * The responsibility with closing the returned stream lies with the caller.
     */
    suspend fun loadFile(handle: AppBackupFileType): InputStream {
        // We load the entire ciphertext into memory,
        // so we can check the SHA-256 hash before decrypting and parsing the data.
        val cipherText = backendManager.backend.load(handle).use { inputStream ->
            inputStream.readAllBytes()
        }
        // check SHA-256 hash first thing
        val sha256 = crypto.sha256(cipherText).toHexString()
        val expectedHash = when (handle) {
            is AppBackupFileType.Snapshot -> handle.hash
            is AppBackupFileType.Blob -> handle.name
        }
        if (sha256 != expectedHash) {
            throw GeneralSecurityException("File had wrong SHA-256 hash: $handle")
        }
        // check that we can handle the version of that snapshot
        val version = cipherText[0]
        if (version <= 1) throw GeneralSecurityException("Unexpected version: $version")
        if (version > VERSION) throw UnsupportedVersionException(version)
        // get associated data for version, used for authenticated decryption
        val ad = crypto.getAdForVersion(version)
        // skip first version byte when creating cipherText stream
        val inputStream = ByteArrayInputStream(cipherText, 1, cipherText.size - 1)
        // decrypt and decompress cipherText stream and parse snapshot
        return ZstdInputStream(crypto.newDecryptingStream(inputStream, ad))
    }

    suspend fun loadFiles(handles: List<AppBackupFileType>): InputStream {
        val enumeration: Enumeration<InputStream> = object : Enumeration<InputStream> {
            val iterator = handles.iterator()

            override fun hasMoreElements(): Boolean {
                return iterator.hasNext()
            }

            override fun nextElement(): InputStream {
                return runBlocking { loadFile(iterator.next()) }
            }
        }
        return SequenceInputStream(enumeration)
    }
}
