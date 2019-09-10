package com.stevesoltys.backup.metadata

import com.stevesoltys.backup.crypto.Crypto
import com.stevesoltys.backup.getRandomString
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import kotlin.random.Random

@TestInstance(PER_CLASS)
internal class MetadataWriterDecoderTest {

    private val crypto = mockk<Crypto>()

    private val encoder = MetadataWriterImpl(crypto)
    private val decoder = MetadataDecoderImpl()

    private val metadata = BackupMetadata(
            version = Random.nextBytes(1)[0],
            token = Random.nextLong(),
            androidVersion = Random.nextInt(),
            deviceName = getRandomString()
    )

    @Test
    fun `encoded metadata matches decoded metadata`() {
        assertEquals(metadata, decoder.decode(encoder.encode(metadata), metadata.version, metadata.token))
    }

}
