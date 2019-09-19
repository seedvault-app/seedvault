package com.stevesoltys.backup.transport

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import com.stevesoltys.backup.crypto.Crypto
import com.stevesoltys.backup.settings.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD

@TestInstance(PER_METHOD)
abstract class TransportTest {

    protected val crypto = mockk<Crypto>()
    protected val settingsManager = mockk<SettingsManager>()
    protected val context = mockk<Context>(relaxed = true)

    protected val packageInfo = PackageInfo().apply { packageName = "org.example" }

    init {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), ofType(String::class)) } returns 0
        every { Log.w(any(), ofType(String::class), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

}
