package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.util.Utf8
import com.stevesoltys.seedvault.service.crypto.CryptoService
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.service.metadata.BackupMetadata
import com.stevesoltys.seedvault.service.metadata.BackupType
import com.stevesoltys.seedvault.service.metadata.JSON_METADATA
import com.stevesoltys.seedvault.service.metadata.JSON_METADATA_SDK_INT
import com.stevesoltys.seedvault.service.metadata.JSON_METADATA_TOKEN
import com.stevesoltys.seedvault.service.metadata.JSON_METADATA_VERSION
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_BACKUP_TYPE
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_INSTALLER
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_SHA256
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_SIGNATURES
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_STATE
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_TIME
import com.stevesoltys.seedvault.service.metadata.JSON_PACKAGE_VERSION
import com.stevesoltys.seedvault.service.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.service.metadata.PackageState.UNKNOWN_ERROR
import com.stevesoltys.seedvault.service.metadata.METADATA_SALT_SIZE
import com.stevesoltys.seedvault.service.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.service.metadata.MetadataWriterImpl
import com.stevesoltys.seedvault.service.metadata.PackageMetadata
import com.stevesoltys.seedvault.service.metadata.PackageMetadataMap
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
class MetadataReaderTest {

    private val cryptoService = mockk<CryptoService>()

    private val encoder = MetadataWriterImpl(cryptoService)
    private val decoder = MetadataReaderImpl(cryptoService)

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
            put(
                "org.example", PackageMetadata(
                    time = Random.nextLong(),
                    state = QUOTA_EXCEEDED,
                    backupType = BackupType.FULL,
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString())
                )
            )
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
            put(JSON_PACKAGE_SHA256, getRandomString())
            put(JSON_PACKAGE_SIGNATURES, JSONArray(listOf(getRandomString(), getRandomString())))
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        assertThrows(SecurityException::class.java) {
            decoder.decode(jsonBytes, metadata.version, metadata.token)
        }
    }

    @Test
    fun `package metadata unknown state gets mapped to error`() {
        val json = JSONObject(metadataByteArray.toString(Utf8))
        json.put("org.example", JSONObject().apply {
            put(JSON_PACKAGE_TIME, Random.nextLong())
            put(JSON_PACKAGE_STATE, getRandomString())
            put(JSON_PACKAGE_BACKUP_TYPE, BackupType.FULL.name)
            put(JSON_PACKAGE_VERSION, Random.nextLong())
            put(JSON_PACKAGE_INSTALLER, getRandomString())
            put(JSON_PACKAGE_SHA256, getRandomString())
            put(JSON_PACKAGE_SIGNATURES, JSONArray(listOf(getRandomString(), getRandomString())))
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        val metadata = decoder.decode(jsonBytes, metadata.version, metadata.token)
        assertEquals(this.metadata.salt, metadata.salt)
        assertEquals(UNKNOWN_ERROR, metadata.packageMetadataMap["org.example"]!!.state)
        assertEquals(BackupType.FULL, metadata.packageMetadataMap["org.example"]!!.backupType)
    }

    @Test
    fun `package metadata missing system gets mapped to false`() {
        val json = JSONObject(metadataByteArray.toString(Utf8))
        json.put("org.example", JSONObject().apply {
            put(JSON_PACKAGE_TIME, Random.nextLong())
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        val metadata = decoder.decode(jsonBytes, metadata.version, metadata.token)
        assertFalse(metadata.packageMetadataMap["org.example"]!!.system)
        assertNull(metadata.packageMetadataMap["org.example"]!!.backupType)
    }

    @Test
    fun `package metadata can only include time`() {
        val json = JSONObject(metadataByteArray.toString(Utf8))
        json.put("org.example", JSONObject().apply {
            put(JSON_PACKAGE_TIME, Random.nextLong())
            put(JSON_PACKAGE_BACKUP_TYPE, BackupType.KV.name)
        })
        val jsonBytes = json.toString().toByteArray(Utf8)
        val result = decoder.decode(jsonBytes, metadata.version, metadata.token)

        assertEquals(1, result.packageMetadataMap.size)
        val packageMetadata = result.packageMetadataMap.getOrElse("org.example") { fail() }
        assertEquals(BackupType.KV, packageMetadata.backupType)
        assertNull(packageMetadata.version)
        assertNull(packageMetadata.installer)
        assertNull(packageMetadata.signatures)
    }

    private fun getMetadata(
        packageMetadata: PackageMetadataMap = PackageMetadataMap(),
    ): BackupMetadata {
        return BackupMetadata(
            version = 1.toByte(),
            token = Random.nextLong(),
            salt = getRandomBase64(METADATA_SALT_SIZE),
            time = Random.nextLong(),
            androidVersion = Random.nextInt(),
            androidIncremental = getRandomString(),
            deviceName = getRandomString(),
            packageMetadataMap = packageMetadata
        )
    }

}
