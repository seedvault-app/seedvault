/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random
import kotlin.random.nextLong

@TestInstance(PER_CLASS)
internal class MetadataWriterDecoderTest {

    private val crypto = mockk<Crypto>()

    private val encoder = MetadataWriterImpl(crypto)
    private val decoder = MetadataReaderImpl(crypto)

    @Test
    fun `encoded metadata matches decoded metadata (no packages)`() {
        val metadata = getMetadata().let {
            if (it.version == 0.toByte()) it.copy(salt = "") // no salt in version 0
            else it
        }
        assertEquals(
            metadata,
            decoder.decode(encoder.encode(metadata), metadata.version, metadata.token)
        )
    }

    @Test
    fun `encoded metadata matches decoded metadata (with package, no apk info)`() {
        val time = Random.nextLong()
        val packages = HashMap<String, PackageMetadata>().apply {
            put(getRandomString(), PackageMetadata(time, APK_AND_DATA, BackupType.FULL))
            put(getRandomString(), PackageMetadata(time, WAS_STOPPED, BackupType.KV))
        }
        val metadata = getMetadata(packages)
        assertEquals(
            metadata,
            decoder.decode(encoder.encode(metadata), metadata.version, metadata.token)
        )
    }

    @Test
    fun `encoded metadata matches decoded metadata (full package)`() {
        val packages = HashMap<String, PackageMetadata>().apply {
            put(
                getRandomString(), PackageMetadata(
                    time = Random.nextLong(),
                    state = APK_AND_DATA,
                    backupType = BackupType.FULL,
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    splits = listOf(
                        ApkSplit(getRandomString(), null, getRandomString()),
                        ApkSplit(getRandomString(), 0L, getRandomString()),
                        ApkSplit(
                            name = getRandomString(),
                            size = Random.nextLong(0, Long.MAX_VALUE),
                            sha256 = getRandomString(),
                        ),
                    ),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString())
                )
            )
        }
        val metadata = getMetadata(packages)
        assertEquals(
            metadata,
            decoder.decode(encoder.encode(metadata), metadata.version, metadata.token)
        )
    }

    @Test
    fun `encoded metadata matches decoded metadata (three full packages)`() {
        val packages = HashMap<String, PackageMetadata>().apply {
            put(
                getRandomString(), PackageMetadata(
                    time = Random.nextLong(),
                    state = QUOTA_EXCEEDED,
                    backupType = BackupType.FULL,
                    size = Random.nextLong(0..Long.MAX_VALUE),
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString()),
                )
            )
            put(
                getRandomString(), PackageMetadata(
                    time = Random.nextLong(),
                    state = NO_DATA,
                    backupType = BackupType.KV,
                    size = null,
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString()),
                )
            )
            put(
                getRandomString(), PackageMetadata(
                    time = 0L,
                    state = NOT_ALLOWED,
                    size = 0,
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString()),
                )
            )
        }
        val metadata = getMetadata(packages)
        assertEquals(
            metadata,
            decoder.decode(encoder.encode(metadata), metadata.version, metadata.token)
        )
    }

    private fun getMetadata(
        packageMetadata: HashMap<String, PackageMetadata> = HashMap(),
    ): BackupMetadata {
        return BackupMetadata(
            version = Random.nextBytes(1)[0],
            token = Random.nextLong(),
            salt = getRandomBase64(32),
            time = Random.nextLong(),
            androidVersion = Random.nextInt(),
            androidIncremental = getRandomString(),
            deviceName = getRandomString(),
            packageMetadataMap = packageMetadata
        )
    }

}
