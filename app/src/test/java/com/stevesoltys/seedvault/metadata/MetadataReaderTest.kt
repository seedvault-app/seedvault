/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
class MetadataReaderTest {

    private val crypto = mockk<Crypto>()

    private val encoder = MetadataWriterImpl()
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
    fun `malformed JSON throws SecurityException`() {
        assertThrows(SecurityException::class.java) {
            decoder.decode("{".toByteArray(Utf8), metadata.version, metadata.token)
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
