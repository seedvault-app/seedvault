package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.getRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [29], // robolectric does not support 30, yet
    application = TestApp::class
)
internal class DeviceInfoTest {

    @After
    fun afterEachTest() {
        stopKoin()
    }

    @Test
    fun `test with mocked context`() {
        val context: Context = mockk()
        val resources: Resources = mockk()
        val onlyOnSameDevice = Random.nextBoolean()

        every { context.resources } returns resources
        every { resources.displayMetrics } returns DisplayMetrics().apply {
            this.densityDpi = 1337
        }
        every { resources.getStringArray(any()) } returns arrayOf("foo-123", "bar-rev")
        every {
            resources.getBoolean(R.bool.re_install_unknown_splits_only_on_same_device)
        } returns onlyOnSameDevice

        val deviceInfo = DeviceInfo(context)

        // the ABI comes from robolectric
        assertEquals(listOf("armeabi-v7a"), deviceInfo.supportedABIs)

        // check that density is returned as expected
        assertEquals(1337, deviceInfo.densityDpi)

        // test languages results are as expected
        assertTrue(deviceInfo.isSupportedLanguage("foo"))
        assertTrue(deviceInfo.isSupportedLanguage("bar"))
        assertFalse(deviceInfo.isSupportedLanguage("en"))
        assertFalse(deviceInfo.isSupportedLanguage("de"))

        // test areUnknownSplitsAllowed
        val deviceName = "unknown robolectric"
        if (onlyOnSameDevice) {
            assertTrue(deviceInfo.areUnknownSplitsAllowed(deviceName))
            assertFalse(deviceInfo.areUnknownSplitsAllowed("foo bar"))
        } else {
            assertTrue(deviceInfo.areUnknownSplitsAllowed(deviceName))
            assertTrue(deviceInfo.areUnknownSplitsAllowed("foo bar"))
        }
    }

    @Test
    fun `test supported languages`() {
        val deviceInfo = DeviceInfo(ApplicationProvider.getApplicationContext())

        assertTrue(deviceInfo.isSupportedLanguage("en"))
        assertTrue(deviceInfo.isSupportedLanguage("de"))
        assertTrue(deviceInfo.isSupportedLanguage("gu"))
        assertTrue(deviceInfo.isSupportedLanguage("pt"))

        assertFalse(deviceInfo.isSupportedLanguage("foo"))
        assertFalse(deviceInfo.isSupportedLanguage("bar"))
        assertFalse(deviceInfo.isSupportedLanguage(getRandomString()))
        assertFalse(deviceInfo.isSupportedLanguage(getRandomString()))
    }

}
