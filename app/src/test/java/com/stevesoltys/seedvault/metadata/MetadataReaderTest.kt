package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.getRandomString
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
class MetadataReaderTest {

    private val crypto = mockk<Crypto>()

    private val encoder = MetadataWriterImpl(crypto)
    private val decoder = MetadataReaderImpl(crypto)

    private val metadata = getMetadata()
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
        val json = JSONObject().apply {
            put(JSON_METADATA, JSONObject().apply {
                put(JSON_METADATA_VERSION, metadata.version.toInt())
                put(JSON_METADATA_TOKEN, metadata.token)
                put(JSON_METADATA_SDK_INT, metadata.androidVersion)
            })
        }
        val jsonBytes = json.toString().toByteArray(Utf8)

        assertThrows(SecurityException::class.java) {
            decoder.decode(jsonBytes, metadata.version, metadata.token)
        }
    }

    @Test
    fun `missing meta throws SecurityException`() {
        val json = JSONObject().apply {
            put("foo", "bat")
        }
        val jsonBytes = json.toString().toByteArray(Utf8)

        assertThrows(SecurityException::class.java) {
            decoder.decode(jsonBytes, metadata.version, metadata.token)
        }
    }

    @Test
    fun `package metadata gets read`() {
        val packageMetadata = HashMap<String, PackageMetadata>().apply {
            put("org.example", PackageMetadata(
                    time = Random.nextLong(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString())
            ))
        }
        val metadata = getMetadata(packageMetadata)
        val metadataByteArray = encoder.encode(metadata)
        decoder.decode(metadataByteArray, metadata.version, metadata.token)
    }

    @Test
    fun `package metadata with missing time throws`() {
        val json = JSONObject(metadataByteArray.toString(Utf8))
        json.put("org.example", JSONObject().apply {
            put(JSON_PACKAGE_VERSION, Random.nextLong())
            put(JSON_PACKAGE_INSTALLER, getRandomString())
            put(JSON_PACKAGE_SIGNATURES, JSONArray(listOf(getRandomString(), getRandomString())))
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        assertThrows(SecurityException::class.java) {
            decoder.decode(jsonBytes, metadata.version, metadata.token)
        }
    }

    @Test
    fun `package metadata can only include time`() {
        val json = JSONObject(metadataByteArray.toString(Utf8))
        json.put("org.example", JSONObject().apply {
            put(JSON_PACKAGE_TIME, Random.nextLong())
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        val result = decoder.decode(jsonBytes, metadata.version, metadata.token)

        assertEquals(1, result.packageMetadata.size)
        val packageMetadata = result.packageMetadata.getOrElse("org.example") { fail() }
        assertNull(packageMetadata.version)
        assertNull(packageMetadata.installer)
        assertNull(packageMetadata.signatures)
    }

    private fun getMetadata(packageMetadata: HashMap<String, PackageMetadata> = HashMap()): BackupMetadata {
        return BackupMetadata(
                version = 1.toByte(),
                token = Random.nextLong(),
                time = Random.nextLong(),
                androidVersion = Random.nextInt(),
                androidIncremental = getRandomString(),
                deviceName = getRandomString(),
                packageMetadata = packageMetadata
        )
    }

}
