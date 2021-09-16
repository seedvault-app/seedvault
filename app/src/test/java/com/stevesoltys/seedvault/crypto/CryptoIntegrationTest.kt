package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.assertReadEquals
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

@TestInstance(PER_METHOD)
class CryptoIntegrationTest {

    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val crypto = CryptoImpl(keyManager, cipherFactory, headerReader)

    private val cleartext = Random.nextBytes(Random.nextInt(1, 422300))

    @Test
    fun `sanity check for getRandomBytes()`() {
        assertThat(crypto.getRandomBytes(42), not(equalTo(crypto.getRandomBytes(42))))
        assertThat(crypto.getRandomBytes(42), not(equalTo(crypto.getRandomBytes(42))))
        assertThat(crypto.getRandomBytes(42), not(equalTo(crypto.getRandomBytes(42))))
        assertThat(crypto.getRandomBytes(42), not(equalTo(crypto.getRandomBytes(42))))
    }

    @Test
    fun `decrypting encrypted cleartext works`() {
        val ad = Random.nextBytes(42)
        val outputStream = ByteArrayOutputStream()
        crypto.newEncryptingStream(outputStream, ad).use { it.write(cleartext) }
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        crypto.newDecryptingStream(inputStream, ad).use {
            assertReadEquals(cleartext, it)
        }
    }

    @Test
    fun `decrypting encrypted cleartext fails with different AD`() {
        val outputStream = ByteArrayOutputStream()
        crypto.newEncryptingStream(outputStream, Random.nextBytes(42)).use { it.write(cleartext) }
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        assertThrows(IOException::class.java) {
            crypto.newDecryptingStream(inputStream, Random.nextBytes(41)).use {
                it.read()
            }
        }
    }

}
