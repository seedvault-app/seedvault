package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.service.crypto.CryptoService
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.service.metadata.ApkSplit
import com.stevesoltys.seedvault.service.metadata.BackupMetadata
import com.stevesoltys.seedvault.service.metadata.BackupType
import com.stevesoltys.seedvault.service.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.service.metadata.MetadataWriterImpl
import com.stevesoltys.seedvault.service.metadata.PackageMetadata
import com.stevesoltys.seedvault.service.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.service.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.service.metadata.PackageState.NO_DATA
import com.stevesoltys.seedvault.service.metadata.PackageState.QUOTA_EXCEEDED
import com.stevesoltys.seedvault.service.metadata.PackageState.WAS_STOPPED
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
internal class MetadataWriterDecoderTest {

    private val cryptoService = mockk<CryptoService>()

    private val encoder = MetadataWriterImpl(cryptoService)
    private val decoder = MetadataReaderImpl(cryptoService)

    @Test
    fun `encoded metadata matches decoded metadata (no packages)`() {
        val metadata = getMetadata()
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
                        ApkSplit(getRandomString(), getRandomString()),
                        ApkSplit(getRandomString(), getRandomString()),
                        ApkSplit(getRandomString(), getRandomString())
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
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString())
                )
            )
            put(
                getRandomString(), PackageMetadata(
                    time = Random.nextLong(),
                    state = NO_DATA,
                    backupType = BackupType.KV,
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
                    sha256 = getRandomString(),
                    signatures = listOf(getRandomString(), getRandomString())
                )
            )
            put(
                getRandomString(), PackageMetadata(
                    time = 0L,
                    state = NOT_ALLOWED,
                    system = Random.nextBoolean(),
                    version = Random.nextLong(),
                    installer = getRandomString(),
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
