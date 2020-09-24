package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.assertContains
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.IV_SIZE
import com.stevesoltys.seedvault.header.MAX_KEY_LENGTH_SIZE
import com.stevesoltys.seedvault.header.MAX_PACKAGE_LENGTH_SIZE
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.MAX_VERSION_HEADER_SIZE
import com.stevesoltys.seedvault.header.SegmentHeader
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.VersionHeader
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import kotlin.random.Random

@TestInstance(PER_METHOD)
class CryptoTest {

    private val cipherFactory = mockk<CipherFactory>()
    private val headerWriter = mockk<HeaderWriter>()
    private val headerReader = mockk<HeaderReader>()

    private val crypto = CryptoImpl(cipherFactory, headerWriter, headerReader)

    private val cipher = mockk<Cipher>()

    private val iv = getRandomByteArray(IV_SIZE)
    private val cleartext = getRandomByteArray(Random.nextInt(MAX_SEGMENT_LENGTH))
    private val ciphertext = getRandomByteArray(Random.nextInt(MAX_SEGMENT_LENGTH))
    private val versionHeader = VersionHeader(
        VERSION,
        getRandomString(MAX_PACKAGE_LENGTH_SIZE),
        getRandomString(MAX_KEY_LENGTH_SIZE)
    )
    private val versionCiphertext = getRandomByteArray(MAX_VERSION_HEADER_SIZE)
    private val versionSegmentHeader = SegmentHeader(versionCiphertext.size.toShort(), iv)
    private val outputStream = ByteArrayOutputStream()
    private val segmentHeader = SegmentHeader(ciphertext.size.toShort(), iv)

    // the headerReader will not actually read the header, so only insert cipher text
    private val inputStream = ByteArrayInputStream(ciphertext)
    private val versionInputStream = ByteArrayInputStream(versionCiphertext)

    // encrypting

    @Test
    fun `encrypt header works as expected`() {
        val segmentHeader = CapturingSlot<SegmentHeader>()
        every { headerWriter.getEncodedVersionHeader(versionHeader) } returns ciphertext
        encryptSegmentHeader(ciphertext, segmentHeader)

        crypto.encryptHeader(outputStream, versionHeader)
        assertArrayEquals(iv, segmentHeader.captured.nonce)
        assertEquals(ciphertext.size, segmentHeader.captured.segmentLength.toInt())
    }

    @Test
    fun `encrypting segment works as expected`() {
        val segmentHeader = CapturingSlot<SegmentHeader>()
        encryptSegmentHeader(cleartext, segmentHeader)

        crypto.encryptSegment(outputStream, cleartext)

        assertArrayEquals(ciphertext, outputStream.toByteArray())
        assertArrayEquals(iv, segmentHeader.captured.nonce)
        assertEquals(ciphertext.size, segmentHeader.captured.segmentLength.toInt())
    }

    private fun encryptSegmentHeader(
        toEncrypt: ByteArray,
        segmentHeader: CapturingSlot<SegmentHeader>
    ) {
        every { cipherFactory.createEncryptionCipher() } returns cipher
        every { cipher.getOutputSize(toEncrypt.size) } returns toEncrypt.size
        every { cipher.iv } returns iv
        every { headerWriter.writeSegmentHeader(outputStream, capture(segmentHeader)) } just Runs
        every { cipher.doFinal(toEncrypt) } returns ciphertext
    }

    // decrypting

    @Test
    fun `decrypting header works as expected`() {
        every { headerReader.readSegmentHeader(versionInputStream) } returns versionSegmentHeader
        every { cipherFactory.createDecryptionCipher(iv) } returns cipher
        every { cipher.doFinal(versionCiphertext) } returns cleartext
        every { headerReader.getVersionHeader(cleartext) } returns versionHeader

        assertEquals(
            versionHeader,
            crypto.decryptHeader(
                versionInputStream,
                versionHeader.version,
                versionHeader.packageName,
                versionHeader.key
            )
        )
    }

    @Test
    fun `decrypting header throws if too large`() {
        val size = MAX_VERSION_HEADER_SIZE + 1
        val versionCiphertext = getRandomByteArray(size)
        val versionInputStream = ByteArrayInputStream(versionCiphertext)
        val versionSegmentHeader = SegmentHeader(size.toShort(), iv)

        every { headerReader.readSegmentHeader(versionInputStream) } returns versionSegmentHeader

        val e = assertThrows(SecurityException::class.java) {
            crypto.decryptHeader(
                versionInputStream,
                versionHeader.version,
                versionHeader.packageName,
                versionHeader.key
            )
        }
        assertContains(e.message, size.toString())
    }

    @Test
    fun `decrypting header throws because of different version`() {
        every { headerReader.readSegmentHeader(versionInputStream) } returns versionSegmentHeader
        every { cipherFactory.createDecryptionCipher(iv) } returns cipher
        every { cipher.doFinal(versionCiphertext) } returns cleartext
        every { headerReader.getVersionHeader(cleartext) } returns versionHeader

        val version = (VERSION + 1).toByte()
        val e = assertThrows(SecurityException::class.java) {
            crypto.decryptHeader(
                versionInputStream,
                version,
                versionHeader.packageName,
                versionHeader.key
            )
        }
        assertContains(e.message, version.toString())
    }

    @Test
    fun `decrypting header throws because of different package name`() {
        every { headerReader.readSegmentHeader(versionInputStream) } returns versionSegmentHeader
        every { cipherFactory.createDecryptionCipher(iv) } returns cipher
        every { cipher.doFinal(versionCiphertext) } returns cleartext
        every { headerReader.getVersionHeader(cleartext) } returns versionHeader

        val packageName = getRandomString(MAX_PACKAGE_LENGTH_SIZE)
        val e = assertThrows(SecurityException::class.java) {
            crypto.decryptHeader(
                versionInputStream,
                versionHeader.version,
                packageName,
                versionHeader.key
            )
        }
        assertContains(e.message, packageName)
    }

    @Test
    fun `decrypting header throws because of different key`() {
        every { headerReader.readSegmentHeader(versionInputStream) } returns versionSegmentHeader
        every { cipherFactory.createDecryptionCipher(iv) } returns cipher
        every { cipher.doFinal(versionCiphertext) } returns cleartext
        every { headerReader.getVersionHeader(cleartext) } returns versionHeader

        val e = assertThrows(SecurityException::class.java) {
            crypto.decryptHeader(
                versionInputStream,
                versionHeader.version,
                versionHeader.packageName,
                null
            )
        }
        assertContains(e.message, "null")
        assertContains(e.message, versionHeader.key ?: fail())
    }

    @Test
    fun `decrypting data segment header works as expected`() {
        every { headerReader.readSegmentHeader(inputStream) } returns segmentHeader
        every { cipherFactory.createDecryptionCipher(iv) } returns cipher
        every { cipher.doFinal(ciphertext) } returns cleartext

        assertArrayEquals(cleartext, crypto.decryptSegment(inputStream))
    }

    @Test
    fun `decrypting data segment throws if reading 0 bytes`() {
        val inputStream = mockk<InputStream>()
        val buffer = ByteArray(segmentHeader.segmentLength.toInt())

        every { headerReader.readSegmentHeader(inputStream) } returns segmentHeader
        every { inputStream.read(buffer) } returns 0

        assertThrows(IOException::class.java) {
            crypto.decryptSegment(inputStream)
        }
    }

    @Test
    fun `decrypting data segment throws if reaching end of stream`() {
        val inputStream = mockk<InputStream>()
        val buffer = ByteArray(segmentHeader.segmentLength.toInt())

        every { headerReader.readSegmentHeader(inputStream) } returns segmentHeader
        every { inputStream.read(buffer) } returns -1

        assertThrows(EOFException::class.java) {
            crypto.decryptSegment(inputStream)
        }
    }

    @Test
    fun `decrypting data segment throws if reading less than expected`() {
        val inputStream = mockk<InputStream>()
        val buffer = ByteArray(segmentHeader.segmentLength.toInt())

        every { headerReader.readSegmentHeader(inputStream) } returns segmentHeader
        every { inputStream.read(buffer) } returns buffer.size - 1

        assertThrows(IOException::class.java) {
            crypto.decryptSegment(inputStream)
        }
    }

}
