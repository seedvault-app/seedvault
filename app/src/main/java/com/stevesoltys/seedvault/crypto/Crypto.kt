package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.MAX_SEGMENT_CLEARTEXT_LENGTH
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.MAX_VERSION_HEADER_SIZE
import com.stevesoltys.seedvault.header.SegmentHeader
import com.stevesoltys.seedvault.header.VersionHeader
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * A backup stream starts with a version byte followed by an encrypted [VersionHeader].
 *
 * The header will be encrypted with AES/GCM to provide authentication.
 * It can be written using [encryptHeader] and read using [decryptHeader].
 * The latter throws a [SecurityException],
 * if the expected version and package name do not match the encrypted header.
 *
 * After the header, follows one or more data segments.
 * Each segment begins with a clear-text [SegmentHeader]
 * that contains the length of the segment
 * and a nonce acting as the initialization vector for the encryption.
 * The segment can be written using [encryptSegment] and read using [decryptSegment].
 * The latter throws a [SecurityException],
 * if the length of the segment is specified larger than allowed.
 */
interface Crypto {

    /**
     * Encrypts a backup stream header ([VersionHeader]) and writes it to the given [OutputStream].
     *
     * The header using a small segment containing only
     * the version number, the package name and (optionally) the key of a key/value stream.
     */
    @Throws(IOException::class)
    fun encryptHeader(outputStream: OutputStream, versionHeader: VersionHeader)

    /**
     * Encrypts a new backup segment from the given cleartext payload
     * and writes it to the given [OutputStream].
     *
     * A segment starts with a [SegmentHeader] which includes the length of the segment
     * and a nonce which is used as initialization vector for the encryption.
     *
     * After the header follows the encrypted payload.
     * Larger backup streams such as from a full backup are encrypted in multiple segments
     * to avoid having to load the entire stream into memory when doing authenticated encryption.
     *
     * The given cleartext can later be decrypted
     * by calling [decryptSegment] on the same byte stream.
     */
    @Throws(IOException::class)
    fun encryptSegment(outputStream: OutputStream, cleartext: ByteArray)

    /**
     * Like [encryptSegment],
     * but if the given cleartext [ByteArray] is larger than [MAX_SEGMENT_CLEARTEXT_LENGTH],
     * multiple segments will be written.
     */
    @Throws(IOException::class)
    fun encryptMultipleSegments(outputStream: OutputStream, cleartext: ByteArray)

    /**
     * Reads and decrypts a [VersionHeader] from the given [InputStream]
     * and ensures that the expected version, package name and key match
     * what is found in the header.
     * If a mismatch is found, a [SecurityException] is thrown.
     *
     * @return The read [VersionHeader] present in the beginning of the given [InputStream].
     */
    @Throws(IOException::class, SecurityException::class)
    fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String? = null
    ): VersionHeader

    /**
     * Reads and decrypts a segment from the given [InputStream].
     *
     * @return The decrypted segment payload as passed into [encryptSegment]
     */
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    fun decryptSegment(inputStream: InputStream): ByteArray

    /**
     * Like [decryptSegment], but decrypts multiple segments and does not throw [EOFException].
     */
    @Throws(IOException::class, SecurityException::class)
    fun decryptMultipleSegments(inputStream: InputStream): ByteArray

    /**
     * Verify that the stored backup key was created from the given seed.
     *
     * @return true if the key was created from given seed, false otherwise.
     */
    fun verifyBackupKey(seed: ByteArray): Boolean
}

internal class CryptoImpl(
    private val cipherFactory: CipherFactory,
    private val headerWriter: HeaderWriter,
    private val headerReader: HeaderReader
) : Crypto {

    @Throws(IOException::class)
    override fun encryptHeader(outputStream: OutputStream, versionHeader: VersionHeader) {
        val bytes = headerWriter.getEncodedVersionHeader(versionHeader)

        encryptSegment(outputStream, bytes)
    }

    @Throws(IOException::class)
    override fun encryptSegment(outputStream: OutputStream, cleartext: ByteArray) {
        val cipher = cipherFactory.createEncryptionCipher()

        check(cipher.getOutputSize(cleartext.size) <= MAX_SEGMENT_LENGTH) {
            "Cipher's output size ${cipher.getOutputSize(cleartext.size)} is larger" +
                "than maximum segment length ($MAX_SEGMENT_LENGTH)"
        }
        encryptSegment(cipher, outputStream, cleartext)
    }

    @Throws(IOException::class)
    override fun encryptMultipleSegments(outputStream: OutputStream, cleartext: ByteArray) {
        var end = 0
        while (end < cleartext.size) {
            val start = end
            end = min(cleartext.size, start + MAX_SEGMENT_CLEARTEXT_LENGTH)
            val segment = cleartext.copyOfRange(start, end)
            val cipher = cipherFactory.createEncryptionCipher()
            encryptSegment(cipher, outputStream, segment)
        }
    }

    @Throws(IOException::class)
    private fun encryptSegment(cipher: Cipher, outputStream: OutputStream, segment: ByteArray) {
        val encrypted = cipher.doFinal(segment)
        val segmentHeader = SegmentHeader(encrypted.size.toShort(), cipher.iv)
        headerWriter.writeSegmentHeader(outputStream, segmentHeader)
        outputStream.write(encrypted)
    }

    @Throws(IOException::class, SecurityException::class)
    override fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String?
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

    @Throws(EOFException::class, IOException::class, SecurityException::class)
    override fun decryptSegment(inputStream: InputStream): ByteArray {
        return decryptSegment(inputStream, MAX_SEGMENT_LENGTH)
    }

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
