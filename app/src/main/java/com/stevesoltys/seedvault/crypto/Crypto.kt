/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.MAX_VERSION_HEADER_SIZE
import com.stevesoltys.seedvault.header.SegmentHeader
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.VersionHeader
import org.calyxos.seedvault.core.crypto.CoreCrypto
import org.calyxos.seedvault.core.crypto.CoreCrypto.ALGORITHM_HMAC
import org.calyxos.seedvault.core.crypto.CoreCrypto.deriveKey
import org.calyxos.seedvault.core.toByteArrayFromHex
import org.calyxos.seedvault.core.toHexString
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
internal interface Crypto {

    /**
     * Returns a ByteArray with bytes retrieved from [SecureRandom].
     */
    fun getRandomBytes(size: Int): ByteArray

    /**
     * Returns the ID of the backup repository as a 64 char hex string.
     */
    val repoId: String

    /**
     * A secret key of size [KEY_SIZE_BYTES]
     * only used to create a gear table specific to each main key.
     */
    val gearTableKey: ByteArray

    fun sha256(bytes: ByteArray): ByteArray

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

    fun getAdForVersion(version: Byte = VERSION): ByteArray

    @Deprecated("only for v1")
    fun getNameForPackage(salt: String, packageName: String): String

    /**
     * Returns the name that identifies an APK in the backup storage plugin.
     * @param suffix empty string for normal APKs and the name of the split in case of an APK split
     */
    @Deprecated("only for v1")
    fun getNameForApk(salt: String, packageName: String, suffix: String = ""): String

    /**
     * Returns a [AesGcmHkdfStreaming] decrypting stream
     * that gets decrypted and authenticated the given associated data.
     */
    @Deprecated("only for v1")
    @Throws(IOException::class, GeneralSecurityException::class)
    fun newDecryptingStreamV1(
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
internal const val TYPE_ICONS: Byte = 0x03

@SuppressLint("HardwareIds")
internal class CryptoImpl(
    context: Context,
    private val keyManager: KeyManager,
    private val cipherFactory: CipherFactory,
    private val headerReader: HeaderReader,
    private val androidId: String = Settings.Secure.getString(context.contentResolver, ANDROID_ID),
) : Crypto {

    private val keyV1: ByteArray by lazy {
        deriveKey(keyManager.getMainKey(), "app data key".toByteArray())
    }
    private val streamKey: ByteArray by lazy {
        deriveKey(keyManager.getMainKey(), "app backup stream key".toByteArray())
    }
    private val secureRandom: SecureRandom by lazy { SecureRandom.getInstanceStrong() }

    override fun getRandomBytes(size: Int) = ByteArray(size).apply {
        secureRandom.nextBytes(this)
    }

    /**
     * The ID of the backup repository tied to this user/device via [ANDROID_ID]
     * and the current [KeyManager.getMainKey].
     *
     * Attention: If the main key ever changes, we need to kill our process,
     * so all lazy values that depend on that key or the [gearTableKey] get reinitialized.
     */
    override val repoId: String by lazy {
        val repoIdKey =
            deriveKey(keyManager.getMainKey(), "app backup repoId key".toByteArray())
        val hmacHasher: Mac = Mac.getInstance(ALGORITHM_HMAC).apply {
            init(SecretKeySpec(repoIdKey, ALGORITHM_HMAC))
        }
        hmacHasher.doFinal(androidId.toByteArrayFromHex()).toHexString()
    }

    override val gearTableKey: ByteArray
        get() = deriveKey(keyManager.getMainKey(), "app backup gear table key".toByteArray())

    override fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray,
    ): OutputStream = CoreCrypto.newEncryptingStream(streamKey, outputStream, associatedData)

    override fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream = CoreCrypto.newDecryptingStream(streamKey, inputStream, associatedData)

    override fun getAdForVersion(version: Byte): ByteArray = ByteBuffer.allocate(1)
        .put(version)
        .array()

    @Deprecated("only for v1")
    override fun getNameForPackage(salt: String, packageName: String): String {
        return sha256("$salt$packageName".toByteArray()).encodeBase64()
    }

    @Deprecated("only for v1")
    override fun getNameForApk(salt: String, packageName: String, suffix: String): String {
        return sha256("${salt}APK$packageName$suffix".toByteArray()).encodeBase64()
    }

    override fun sha256(bytes: ByteArray): ByteArray {
        val messageDigest: MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
        messageDigest.update(bytes)
        return messageDigest.digest()
    }

    @Deprecated("only for v1")
    @Throws(IOException::class, GeneralSecurityException::class)
    override fun newDecryptingStreamV1(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream = CoreCrypto.newDecryptingStream(keyV1, inputStream, associatedData)

    @Suppress("Deprecation")
    @Throws(IOException::class, SecurityException::class)
    @Deprecated("Use newDecryptingStream instead")
    override fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String?,
    ): VersionHeader {
        val decrypted = decryptSegment(inputStream, MAX_VERSION_HEADER_SIZE)
        val header = headerReader.getVersionHeader(decrypted)

        if (header.version != expectedVersion) {
            throw SecurityException(
                "Invalid version '${header.version.toInt()}' in header, " +
                    "expected '${expectedVersion.toInt()}'."
            )
        }
        if (header.packageName != expectedPackageName) {
            throw SecurityException(
                "Invalid package name '${header.packageName}' in header, " +
                    "expected '$expectedPackageName'."
            )
        }
        if (header.key != expectedKey) throw SecurityException(
            "Invalid key '${header.key}' in header, expected '$expectedKey'."
        )

        return header
    }

    @Deprecated("Use newDecryptingStream instead")
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    override fun decryptSegment(inputStream: InputStream): ByteArray {
        return decryptSegment(inputStream, MAX_SEGMENT_LENGTH)
    }

    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    override fun decryptMultipleSegments(inputStream: InputStream): ByteArray {
        var result = ByteArray(0)
        while (true) {
            try {
                result += decryptSegment(inputStream, MAX_SEGMENT_LENGTH)
            } catch (e: EOFException) {
                if (result.isEmpty()) throw IOException(e)
                return result
            }
        }
    }

    @Suppress("Deprecation")
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    private fun decryptSegment(inputStream: InputStream, maxSegmentLength: Int): ByteArray {
        val segmentHeader = headerReader.readSegmentHeader(inputStream)
        if (segmentHeader.segmentLength > maxSegmentLength) throw SecurityException(
            "Segment length too long: ${segmentHeader.segmentLength} > $maxSegmentLength"
        )

        val buffer = ByteArray(segmentHeader.segmentLength.toInt())
        val bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) throw EOFException()
        if (bytesRead != buffer.size) throw IOException()
        val cipher = cipherFactory.createDecryptionCipher(segmentHeader.nonce)

        return cipher.doFinal(buffer)
    }

    override fun verifyBackupKey(seed: ByteArray): Boolean {
        // encrypt with stored backup key
        val toEncrypt = "Recovery Code Verification".toByteArray()
        val cipher = cipherFactory.createEncryptionCipher()
        val encrypted = cipher.doFinal(toEncrypt) as ByteArray

        // encrypt with input key cipher
        val secretKeySpec = SecretKeySpec(seed, 0, KEY_SIZE_BYTES, "AES")
        val inputCipher = cipherFactory.createEncryptionTestCipher(secretKeySpec, cipher.iv)
        val inputEncrypted = inputCipher.doFinal(toEncrypt)

        // keys match if encrypted result is the same
        return encrypted.contentEquals(inputEncrypted)
    }

}
