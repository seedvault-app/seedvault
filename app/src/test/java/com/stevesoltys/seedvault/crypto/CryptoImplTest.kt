package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.header.HeaderReaderImpl
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import java.io.ByteArrayInputStream
import java.io.IOException

@TestInstance(PER_METHOD)
class CryptoImplTest {

    private val keyManager = mockk<KeyManager>()
    private val cipherFactory = mockk<CipherFactory>()
    private val headerReader = HeaderReaderImpl()

    private val crypto = CryptoImpl(keyManager, cipherFactory, headerReader)

    @Test
    fun `decrypting multiple segments on empty stream throws`() {
        val inputStream = ByteArrayInputStream(ByteArray(0))
        assertThrows(IOException::class.java) {
            crypto.decryptMultipleSegments(inputStream)
        }
    }

}
