package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KEY_SIZE_BYTES
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import com.stevesoltys.seedvault.metadata.PackageState.WAS_STOPPED
import com.stevesoltys.seedvault.toByteArrayFromHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.io.ByteArrayInputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Tests that we can still decrypt and read version 0 metadata.
 */
@TestInstance(PER_CLASS)
internal class MetadataV0ReadTest {

    private val secretKey = SecretKeySpec(
        "This is a legacy backup key 1234".toByteArray(), 0, KEY_SIZE_BYTES, "AES"
    )
    private val keyManager = KeyManagerTestImpl(secretKey)
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl = CryptoImpl(keyManager, cipherFactory, headerReader)

    private val reader = MetadataReaderImpl(cryptoImpl)

    private val packages = HashMap<String, PackageMetadata>().apply {
        put("org.example", PackageMetadata(23L, APK_AND_DATA))
        put("net.example", PackageMetadata(42L, WAS_STOPPED))
    }

    @Test
    fun `written metadata matches read metadata`() {
        val metadata = getMetadata(packages)
        val encryptedMetadata = ("0000C61EB78225EDD5CBCC4CA693486451C7CF8CAE" +
            "D7BCD7B390262EEC4C4ADF721FB35D0D3EFB33ABAFDFA5634DFA361F523183A82ED284330360B9BEA1AC" +
            "C323EC05DE1C841AD9B57D76F812494FA9A9BBE9FD01DCC878852A4171DFD456BC13EAC70BA973FF7BC1" +
            "75CA84F0324FB35F7AD32CAD5451494B6DE45473519037FCBA50F4DE1CF424552ED813DC782425837B96" +
            "F580B01534FE9C9969E0434796F4599B28A533956E180ABD2823E1822DF9E344EEF8BDE06307815332FE" +
            "19E757B7133EE3853A7C8157F2ECDE82C0AC1D0A9B187573").toByteArrayFromHex()
        val inputStream = ByteArrayInputStream(encryptedMetadata)
        assertEquals(metadata, reader.readMetadata(inputStream, metadata.token))
    }

    private fun getMetadata(
        packageMetadata: HashMap<String, PackageMetadata> = HashMap(),
    ) = BackupMetadata(
        version = 0x00,
        token = 1337L,
        salt = "",
        time = 2342L,
        androidVersion = 30,
        androidIncremental = "sdfqefpojlfj",
        deviceName = "foo bar",
        packageMetadataMap = packageMetadata
    )

}
