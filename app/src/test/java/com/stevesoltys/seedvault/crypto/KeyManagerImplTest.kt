package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.getRandomByteArray
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import junit.framework.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import org.junit.jupiter.api.assertThrows
import java.security.KeyStore

@TestInstance(PER_METHOD)
class KeyManagerImplTest {

    private val keyStore: KeyStore = mockk()
    private val keyManager = KeyManagerImpl(keyStore)

    @Test
    fun `31 byte seed gets rejected for backup key`() {
        val seed = getRandomByteArray(31)
        assertThrows<IllegalArgumentException> {
            keyManager.storeBackupKey(seed)
        }
    }

    @Test
    fun `63 byte seed gets rejected for main key`() {
        val seed = getRandomByteArray(63)
        assertThrows<IllegalArgumentException> {
            keyManager.storeMainKey(seed)
        }
    }

    @Test
    fun `32 byte seed gets accepted for backup key`() {
        val seed = getRandomByteArray(32)
        val keyEntry = slot<KeyStore.SecretKeyEntry>()

        every { keyStore.setEntry(any(), capture(keyEntry), any()) } just Runs

        keyManager.storeBackupKey(seed)

        assertTrue(keyEntry.isCaptured)
        assertArrayEquals(seed.sliceArray(0 until 32), keyEntry.captured.secretKey.encoded)
    }

    @Test
    fun `64 byte seed gets accepted for main key`() {
        val seed = getRandomByteArray(64)
        val keyEntry = slot<KeyStore.SecretKeyEntry>()

        every { keyStore.setEntry(any(), capture(keyEntry), any()) } just Runs

        keyManager.storeMainKey(seed)

        assertTrue(keyEntry.isCaptured)
        assertArrayEquals(seed.sliceArray(32 until 64), keyEntry.captured.secretKey.encoded)
    }

}
