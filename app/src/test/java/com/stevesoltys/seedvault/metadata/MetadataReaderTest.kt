package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.getRandomString
import io.mockk.mockk
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
class MetadataReaderTest {

    private val crypto = mockk<Crypto>()

    private val encoder = MetadataWriterImpl(crypto)
    private val decoder = MetadataReaderImpl(crypto)

    private val metadata = BackupMetadata(
            version = 1.toByte(),
            token = Random.nextLong(),
            androidVersion = Random.nextInt(),
            deviceName = getRandomString()
    )
    private val metadataByteArray = encoder.encode(metadata)

    @Test
    fun `unexpected version should throw SecurityException`() {
        assertThrows(SecurityException::class.java) {
            decoder.decode(metadataByteArray, 2.toByte(), metadata.token)
        }
    }

    @Test
    fun `unexpected token should throw SecurityException`() {
        assertThrows(SecurityException::class.java) {
            decoder.decode(metadataByteArray, metadata.version, metadata.token - 1)
        }
    }

    @Test
    fun `expected version and token do not throw SecurityException`() {
        decoder.decode(metadataByteArray, metadata.version, metadata.token)
    }

    @Test
    fun `malformed JSON throws SecurityException`() {
        assertThrows(SecurityException::class.java) {
            decoder.decode("{".toByteArray(Utf8), metadata.version, metadata.token)
        }
    }

    @Test
    fun `missing fields throws SecurityException`() {
        val json = JSONObject()
        json.put(JSON_VERSION, metadata.version.toInt())
        json.put(JSON_TOKEN, metadata.token)
        json.put(JSON_ANDROID_VERSION, metadata.androidVersion)
        val jsonBytes = json.toString().toByteArray(Utf8)

        assertThrows(SecurityException::class.java) {
            decoder.decode(jsonBytes, metadata.version, metadata.token)
        }
    }

}
