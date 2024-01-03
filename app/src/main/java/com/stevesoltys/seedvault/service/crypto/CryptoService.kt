package com.stevesoltys.seedvault.service.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.stevesoltys.seedvault.service.header.SegmentHeader
import com.stevesoltys.seedvault.service.header.VersionHeader
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * A version 1 backup stream uses [AesGcmHkdfStreaming] from the tink library.
 *
 * A version 0 backup stream starts with a version byte followed by an encrypted [VersionHeader].
 *
 * The header will be encrypted with AES/GCM to provide authentication.
 * It can be read using [decryptHeader] which throws a [SecurityException],
 * if the expected version and package name do not match the encrypted header.
 *
 * After the header, follows one or more data segments.
 * Each segment begins with a clear-text [SegmentHeader]
 * that contains the length of the segment
 * and a nonce acting as the initialization vector for the encryption.
 * The segment can be read using [decryptSegment] which throws a [SecurityException],
 * if the length of the segment is specified larger than allowed.
 */
internal interface CryptoService {

    /**
     * Returns a ByteArray with bytes retrieved from [SecureRandom].
     */
    fun getRandomBytes(size: Int): ByteArray

    fun getNameForPackage(salt: String, packageName: String): String

    /**
     * Returns the name that identifies an APK in the backup storage plugin.
     * @param suffix empty string for normal APKs and the name of the split in case of an APK split
     */
    fun getNameForApk(salt: String, packageName: String, suffix: String = ""): String

    /**
     * Returns a [AesGcmHkdfStreaming] encrypting stream
     * that gets encrypted and authenticated the given associated data.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray,
    ): OutputStream

    /**
     * Returns a [AesGcmHkdfStreaming] decrypting stream
     * that gets decrypted and authenticated the given associated data.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream

    /**
     * Reads and decrypts a [VersionHeader] from the given [InputStream]
     * and ensures that the expected version, package name and key match
     * what is found in the header.
     * If a mismatch is found, a [SecurityException] is thrown.
     *
     * @return The read [VersionHeader] present in the beginning of the given [InputStream].
     */
    @Suppress("Deprecation")
    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String? = null,
    ): VersionHeader

    /**
     * Reads and decrypts a segment from the given [InputStream].
     *
     * @return The decrypted segment payload.
     */
    @Deprecated("Use newDecryptingStream instead")
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    fun decryptSegment(inputStream: InputStream): ByteArray

    /**
     * Like [decryptSegment], but decrypts multiple segments and does not throw [EOFException].
     */
    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    fun decryptMultipleSegments(inputStream: InputStream): ByteArray

    /**
     * Verify that the stored backup key was created from the given seed.
     *
     * @return true if the key was created from given seed, false otherwise.
     */
    fun verifyBackupKey(seed: ByteArray): Boolean
}

internal const val TYPE_METADATA: Byte = 0x00
internal const val TYPE_BACKUP_KV: Byte = 0x01
internal const val TYPE_BACKUP_FULL: Byte = 0x02
