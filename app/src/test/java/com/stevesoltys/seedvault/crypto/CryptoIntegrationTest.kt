package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.header.HeaderWriterImpl
import com.stevesoltys.seedvault.header.MAX_SEGMENT_CLEARTEXT_LENGTH
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@TestInstance(PER_METHOD)
class CryptoIntegrationTest {

    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerWriter = HeaderWriterImpl()
    private val headerReader = HeaderReaderImpl()

    private val crypto = CryptoImpl(keyManager, cipherFactory, headerWriter, headerReader)

    private val cleartext = byteArrayOf(0x01, 0x02, 0x03)

    private val outputStream = ByteArrayOutputStream()

    @Test
    fun `the plain crypto works`() {
        val eCipher = cipherFactory.createEncryptionCipher()
        val encrypted = eCipher.doFinal(cleartext)

        val dCipher = cipherFactory.createDecryptionCipher(eCipher.iv)
        val decrypted = dCipher.doFinal(encrypted)

        assertArrayEquals(cleartext, decrypted)
    }

    @Test
    fun `encrypted cleartext gets decrypted as expected`() {
        crypto.encryptSegment(outputStream, cleartext)
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        assertArrayEquals(cleartext, crypto.decryptSegment(inputStream))
    }

    @Test
    fun `multiple segments get encrypted and decrypted as expected`() {
        val size = Random.nextInt(5) * MAX_SEGMENT_CLEARTEXT_LENGTH + Random.nextInt(0, 1337)
        val cleartext = ByteArray(size).apply { Random.nextBytes(this) }

        crypto.encryptMultipleSegments(outputStream, cleartext)
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        assertArrayEquals(cleartext, crypto.decryptMultipleSegments(inputStream))
    }

    @Test
    fun `test maximum lengths`() {
        val cipher = cipherFactory.createEncryptionCipher()
        val expectedDiff = MAX_SEGMENT_LENGTH - MAX_SEGMENT_CLEARTEXT_LENGTH
        for (i in 1..(3 * MAX_SEGMENT_LENGTH + 42)) {
            val outputSize = cipher.getOutputSize(i)
            assertEquals(expectedDiff, outputSize - i)
        }
    }

}
