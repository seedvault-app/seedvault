package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.header.HeaderWriterImpl
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@TestInstance(PER_METHOD)
class CryptoIntegrationTest {

    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerWriter = HeaderWriterImpl()
    private val headerReader = HeaderReaderImpl()

    private val crypto = CryptoImpl(cipherFactory, headerWriter, headerReader)

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

}
