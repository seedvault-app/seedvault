package com.stevesoltys.seedvault.restore.install

import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class ApkSplitCompatibilityCheckerTest : TransportTest() {

    private val deviceInfo: DeviceInfo = mockk()

    private val checker = ApkSplitCompatibilityChecker(deviceInfo)

    @Test
    fun `non-config splits always get accepted`() {
        assertTrue(
            checker.isCompatible(
                listOf(
                    getRandomString(),
                    getRandomString(),
                    getRandomString(),
                    getRandomString(),
                    getRandomString(),
                    getRandomString()
                )
            )
        )
    }

    @Test
    fun `non-config splits mixed with unknown config splits always get accepted`() {
        assertTrue(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.en",
                    "config.gu",
                    getRandomString(),
                    getRandomString(),
                    getRandomString()
                )
            )
        )
    }

    @Test
    fun `all supported ABIs get accepted, non-supported rejected`() {
        every { deviceInfo.supportedABIs } returns listOf("arm64-v8a", "armeabi-v7a", "armeabi")

        assertTrue(checker.isCompatible(listOf("config.arm64_v8a")))
        assertTrue(checker.isCompatible(listOf("${getRandomString()}.config.arm64_v8a")))
        assertTrue(checker.isCompatible(listOf("config.armeabi_v7a")))
        assertTrue(checker.isCompatible(listOf("${getRandomString()}.config.armeabi_v7a")))
        assertTrue(checker.isCompatible(listOf("config.armeabi")))
        assertTrue(checker.isCompatible(listOf("${getRandomString()}.config.armeabi")))

        assertFalse(checker.isCompatible(listOf("config.x86")))
        assertFalse(checker.isCompatible(listOf("config.x86_64")))
        assertFalse(checker.isCompatible(listOf("config.mips")))
        assertFalse(checker.isCompatible(listOf("config.mips64")))
    }

    @Test
    fun `armeabi rejects arm64_v8a and armeabi-v7a`() {
        every { deviceInfo.supportedABIs } returns listOf("armeabi")

        assertTrue(checker.isCompatible(listOf("config.armeabi")))
        assertTrue(checker.isCompatible(listOf("${getRandomString()}.config.armeabi")))

        assertFalse(checker.isCompatible(listOf("config.arm64_v8a")))
        assertFalse(checker.isCompatible(listOf("${getRandomString()}.config.arm64_v8a")))
        assertFalse(checker.isCompatible(listOf("config.armeabi_v7a")))
        assertFalse(checker.isCompatible(listOf("${getRandomString()}.config.armeabi_v7a")))
    }

    @Test
    fun `screen density is accepted when not too low`() {
        every { deviceInfo.densityDpi } returns 440 // xxhdpi - Pixel 4

        assertTrue(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.xxxhdpi", // higher density is accepted
                    getRandomString()
                )
            )
        )
        assertTrue(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.xxhdpi", // same density is accepted
                    getRandomString()
                )
            )
        )
        assertTrue(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.xhdpi", // one lower density is accepted
                    getRandomString()
                )
            )
        )
        assertFalse(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.hdpi", // two lower density is not accepted
                    getRandomString()
                )
            )
        )
        // even lower densities are also not accepted
        assertFalse(checker.isCompatible(listOf("config.tvdpi")))
        assertFalse(checker.isCompatible(listOf("config.mdpi")))
        assertFalse(checker.isCompatible(listOf("config.ldpi")))
    }

    @Test
    fun `screen density accepts all higher densities`() {
        every { deviceInfo.densityDpi } returns 120

        assertTrue(checker.isCompatible(listOf("config.xxxhdpi")))
        assertTrue(checker.isCompatible(listOf("config.xxhdpi")))
        assertTrue(checker.isCompatible(listOf("config.xhdpi")))
        assertTrue(checker.isCompatible(listOf("config.hdpi")))
        assertTrue(checker.isCompatible(listOf("config.tvdpi")))
        assertTrue(checker.isCompatible(listOf("config.mdpi")))
        assertTrue(checker.isCompatible(listOf("config.ldpi")))
    }

    @Test
    fun `test mix of unknown and all known config splits`() {
        every { deviceInfo.supportedABIs } returns listOf("armeabi-v7a", "armeabi")
        every { deviceInfo.densityDpi } returns 240

        assertTrue(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.xhdpi",
                    "config.armeabi",
                    getRandomString()
                )
            )
        )
        // same as above, but feature split with unsupported ABI config gets rejected
        assertFalse(
            checker.isCompatible(
                listOf(
                    "config.de",
                    "config.xhdpi",
                    "config.armeabi",
                    "${getRandomString()}.config.arm64_v8a",
                    getRandomString()
                )
            )
        )

        assertTrue(checker.isCompatible(listOf("config.xhdpi", "config.armeabi")))
        assertTrue(checker.isCompatible(listOf("config.hdpi", "config.armeabi_v7a")))
        assertFalse(checker.isCompatible(listOf("foo.config.ldpi", "config.armeabi_v7a")))
        assertFalse(checker.isCompatible(listOf("foo.config.xxxhdpi", "bar.config.arm64_v8a")))
    }

}
