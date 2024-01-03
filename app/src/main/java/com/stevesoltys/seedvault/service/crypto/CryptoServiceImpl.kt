package com.stevesoltys.seedvault.service.crypto

import com.stevesoltys.seedvault.service.header.HeaderDecodeService
import com.stevesoltys.seedvault.service.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.service.header.MAX_VERSION_HEADER_SIZE
import com.stevesoltys.seedvault.service.header.VersionHeader
import com.stevesoltys.seedvault.util.encodeBase64
import org.calyxos.backup.storage.crypto.StreamCrypto
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

internal class CryptoServiceImpl(
    private val keyManager: KeyManager,
    private val cipherFactory: CipherFactory,
    private val headerDecodeService: HeaderDecodeService,
) : CryptoService {

    private val key: ByteArray by lazy {
        StreamCrypto.deriveStreamKey(keyManager.getMainKey(), "app data key".toByteArray())
    }
    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    override fun getRandomBytes(size: Int) = ByteArray(size).apply {
        secureRandom.nextBytes(this)
    }

    override fun getNameForPackage(salt: String, packageName: String): String {
        return sha256("$salt$packageName".toByteArray()).encodeBase64()
    }

    override fun getNameForApk(salt: String, packageName: String, suffix: String): String {
        return sha256("${salt}APK$packageName$suffix".toByteArray()).encodeBase64()
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        val messageDigest: MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
        messageDigest.update(bytes)
        return messageDigest.digest()
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray,
    ): OutputStream {
        return StreamCrypto.newEncryptingStream(key, outputStream, associatedData)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream {
        return StreamCrypto.newDecryptingStream(key, inputStream, associatedData)
    }

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
        val header = headerDecodeService.getVersionHeader(decrypted)

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
        val segmentHeader = headerDecodeService.readSegmentHeader(inputStream)
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
