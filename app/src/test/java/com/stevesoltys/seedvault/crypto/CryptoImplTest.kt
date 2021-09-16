package com.stevesoltys.seedvault.crypto

import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.metadata.METADATA_SALT_SIZE
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    @Test
    fun `getNameForPackage() and getNameForApk() return deterministic results`() {
        val salt = getRandomBase64(METADATA_SALT_SIZE)
        val packageName = getRandomString(32)
        assertEquals(
            crypto.getNameForPackage(salt, packageName),
            crypto.getNameForPackage(salt, packageName)
        )
        assertEquals(
            crypto.getNameForApk(salt, packageName),
            crypto.getNameForApk(salt, packageName)
        )
    }

    @Test
    fun `getNameForPackage() and getNameForApk() return different results`() {
        val salt = getRandomBase64(METADATA_SALT_SIZE)
        val packageName = getRandomString(32)
        assertNotEquals(
            crypto.getNameForPackage(salt, packageName),
            crypto.getNameForApk(salt, packageName)
        )
    }

    @Test
    fun `getNameForPackage() and getNameForApk() return different results for different salts`() {
        val packageName = getRandomString(32)
        assertNotEquals(
            crypto.getNameForPackage(getRandomBase64(METADATA_SALT_SIZE), packageName),
            crypto.getNameForPackage(getRandomBase64(METADATA_SALT_SIZE), packageName)
        )
        assertNotEquals(
            crypto.getNameForApk(getRandomBase64(METADATA_SALT_SIZE), packageName),
            crypto.getNameForApk(getRandomBase64(METADATA_SALT_SIZE), packageName)
        )
    }

    @Test
    fun `getNameForPackage() and getNameForApk() return different for different package names`() {
        val salt = getRandomBase64(METADATA_SALT_SIZE)
        assertNotEquals(
            crypto.getNameForPackage(salt, getRandomString(32)),
            crypto.getNameForPackage(salt, getRandomString(32))
        )
        assertNotEquals(
            crypto.getNameForApk(salt, getRandomString(32)),
            crypto.getNameForApk(salt, getRandomString(32))
        )
    }

    @Test
    fun `getNameForApk() return different results for different suffixes`() {
        val salt = getRandomBase64(METADATA_SALT_SIZE)
        val packageName = getRandomString(32)
        assertNotEquals(
            crypto.getNameForApk(salt, packageName, getRandomString(4)),
            crypto.getNameForApk(salt, packageName, getRandomString(5))
        )
    }

}
