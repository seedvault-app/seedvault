package com.stevesoltys.seedvault.restore.install

import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class ApkSplitCompatibilityCheckerTest : TransportTest() {

    private val deviceInfo: DeviceInfo = mockk()
    private val deviceName = getRandomString()

    private val checker = ApkSplitCompatibilityChecker(deviceInfo)

    @Test
    fun `non-config splits always get accepted except when unknowns are not allowed`() {
        val splits = listOf(
            getRandomString(),
            getRandomString(),
            getRandomString(),
            getRandomString(),
            getRandomString(),
            getRandomString()
        )
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns true andThen false
        assertTrue(checker.isCompatible(deviceName, splits))
        assertFalse(checker.isCompatible(deviceName, splits))
    }

    @Test
    fun `non-config splits mixed with language config splits get accepted iff allowed`() {
        val splits = listOf(
            "config.de",
            "config.en",
            "config.gu",
            getRandomString(),
            getRandomString(),
            getRandomString()
        )
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns true andThen false
        every { deviceInfo.isSupportedLanguage("de") } returns true
        every { deviceInfo.isSupportedLanguage("en") } returns true
        every { deviceInfo.isSupportedLanguage("gu") } returns true
        assertTrue(checker.isCompatible(deviceName, splits))
        assertFalse(checker.isCompatible(deviceName, splits))
    }

    @Test
    fun `unknown config splits get rejected if from different device`() {
        val unknownName = getRandomString()
        val splits = listOf("config.$unknownName")
        every { deviceInfo.isSupportedLanguage(unknownName) } returns false

        // reject if on different device
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns Random.nextBoolean()
        every { deviceInfo.isSameDevice(deviceName) } returns false
        assertFalse(checker.isCompatible(deviceName, splits))

        // accept if same device
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns Random.nextBoolean()
        every { deviceInfo.isSameDevice(deviceName) } returns true
        assertTrue(checker.isCompatible(deviceName, splits))
    }

    @Test
    fun `all supported ABIs get accepted, non-supported rejected`() {
        val unknownAllowed = Random.nextBoolean()
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns unknownAllowed
        every { deviceInfo.supportedABIs } returns listOf("arm64-v8a", "armeabi-v7a", "armeabi")

        assertTrue(checker.isCompatible(deviceName, listOf("config.arm64_v8a")))
        assertEquals(
            unknownAllowed,
            checker.isCompatible(deviceName, listOf("${getRandomString()}.config.arm64_v8a"))
        )
        assertTrue(checker.isCompatible(deviceName, listOf("config.armeabi_v7a")))
        assertEquals(
            unknownAllowed,
            checker.isCompatible(deviceName, listOf("${getRandomString()}.config.armeabi_v7a"))
        )
        assertTrue(checker.isCompatible(deviceName, listOf("config.armeabi")))
        assertEquals(
            unknownAllowed,
            checker.isCompatible(deviceName, listOf("${getRandomString()}.config.armeabi"))
        )

        assertFalse(checker.isCompatible(deviceName, listOf("config.x86")))
        assertFalse(checker.isCompatible(deviceName, listOf("config.x86_64")))
        assertFalse(checker.isCompatible(deviceName, listOf("config.mips")))
        assertFalse(checker.isCompatible(deviceName, listOf("config.mips64")))
    }

    @Test
    fun `armeabi rejects arm64_v8a and armeabi-v7a`() {
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns true
        every { deviceInfo.supportedABIs } returns listOf("armeabi")

        assertTrue(checker.isCompatible(deviceName, listOf("config.armeabi")))
        assertTrue(checker.isCompatible(deviceName, listOf("${getRandomString()}.config.armeabi")))

        assertFalse(checker.isCompatible(deviceName, listOf("config.arm64_v8a")))
        assertFalse(
            checker.isCompatible(deviceName, listOf("${getRandomString()}.config.arm64_v8a"))
        )
        assertFalse(checker.isCompatible(deviceName, listOf("config.armeabi_v7a")))
        assertFalse(
            checker.isCompatible(deviceName, listOf("${getRandomString()}.config.armeabi_v7a"))
        )
    }

    @Test
    fun `screen density is accepted when not too low`() {
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns Random.nextBoolean()
        every { deviceInfo.densityDpi } returns 440 // xxhdpi - Pixel 4

        // higher density is accepted
        assertTrue(checker.isCompatible(deviceName, listOf("config.xxxhdpi")))
        // same density is accepted
        assertTrue(checker.isCompatible(deviceName, listOf("config.xxhdpi")))
        // one lower density is accepted
        assertTrue(checker.isCompatible(deviceName, listOf("config.xhdpi")))
        // too low density is not accepted
        assertFalse(checker.isCompatible(deviceName, listOf("config.hdpi")))
        // even lower densities are also not accepted
        assertFalse(checker.isCompatible(deviceName, listOf("config.tvdpi")))
        assertFalse(checker.isCompatible(deviceName, listOf("config.mdpi")))
        assertFalse(checker.isCompatible(deviceName, listOf("config.ldpi")))
    }

    @Test
    fun `screen density accepts all higher densities`() {
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns Random.nextBoolean()
        every { deviceInfo.densityDpi } returns 120

        assertTrue(checker.isCompatible(deviceName, listOf("config.xxxhdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.xxhdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.xhdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.hdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.tvdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.mdpi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.ldpi")))
    }

    @Test
    fun `config splits in feature modules are considered unknown splits`() {
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns false

        assertFalse(
            checker.isCompatible(
                deviceName,
                listOf(
                    "${getRandomString()}.config.xhdpi",
                    "${getRandomString()}.config.arm64_v8a"
                )
            )
        )
    }

    @Test
    fun `test mix of unknown and all known config splits`() {
        val unknownAllowed = Random.nextBoolean()
        every { deviceInfo.areUnknownSplitsAllowed(deviceName) } returns unknownAllowed
        every { deviceInfo.supportedABIs } returns listOf("armeabi-v7a", "armeabi")
        every { deviceInfo.densityDpi } returns 240
        every { deviceInfo.isSupportedLanguage("de") } returns true

        assertEquals(
            unknownAllowed,
            checker.isCompatible(
                deviceName,
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
                deviceName,
                listOf(
                    "config.de",
                    "config.xhdpi",
                    "config.armeabi",
                    "${getRandomString()}.config.arm64_v8a",
                    getRandomString()
                )
            )
        )

        assertTrue(checker.isCompatible(deviceName, listOf("config.xhdpi", "config.armeabi")))
        assertTrue(checker.isCompatible(deviceName, listOf("config.hdpi", "config.armeabi_v7a")))
        assertFalse(
            checker.isCompatible(deviceName, listOf("foo.config.ldpi", "config.armeabi_v7a"))
        )
        assertFalse(
            checker.isCompatible(deviceName, listOf("foo.config.xxxhdpi", "bar.config.arm64_v8a"))
        )
    }

}
