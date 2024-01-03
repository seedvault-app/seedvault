package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.service.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.service.crypto.CryptoServiceImpl
import com.stevesoltys.seedvault.service.crypto.KEY_SIZE_BYTES
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.service.header.HeaderDecodeServiceImpl
import com.stevesoltys.seedvault.service.header.VERSION
import com.stevesoltys.seedvault.service.metadata.BackupMetadata
import com.stevesoltys.seedvault.service.metadata.BackupType
import com.stevesoltys.seedvault.service.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.service.metadata.MetadataWriterImpl
import com.stevesoltys.seedvault.service.metadata.PackageMetadata
import com.stevesoltys.seedvault.service.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.service.metadata.PackageState.WAS_STOPPED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@TestInstance(PER_CLASS)
internal class MetadataReadWriteTest {

    private val secretKey = SecretKeySpec(
        "This is a legacy backup key 1234".toByteArray(), 0, KEY_SIZE_BYTES, "AES"
    )
    private val keyManager = KeyManagerTestImpl(secretKey)
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderDecodeServiceImpl()
    private val cryptoServiceImpl = CryptoServiceImpl(keyManager, cipherFactory, headerReader)

    private val writer = MetadataWriterImpl(cryptoServiceImpl)
    private val reader = MetadataReaderImpl(cryptoServiceImpl)

    private val packages = HashMap<String, PackageMetadata>().apply {
        put(getRandomString(), PackageMetadata(Random.nextLong(), APK_AND_DATA, BackupType.FULL))
        put(getRandomString(), PackageMetadata(Random.nextLong(), WAS_STOPPED, BackupType.KV))
    }

    @Test
    fun `written metadata matches read metadata`() {
        val metadata = getMetadata(packages)
        val outputStream = ByteArrayOutputStream()

        writer.write(metadata, outputStream)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

        assertEquals(metadata, reader.readMetadata(inputStream, metadata.token))
    }

    private fun getMetadata(
        packageMetadata: HashMap<String, PackageMetadata> = HashMap(),
    ): BackupMetadata {
        return BackupMetadata(
            version = VERSION,
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
