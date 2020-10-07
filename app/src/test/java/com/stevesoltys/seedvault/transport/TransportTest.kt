package com.stevesoltys.seedvault.transport

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.PackageInfo
import android.content.pm.SigningInfo
import android.util.Log
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.settings.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import kotlin.random.Random

@TestInstance(PER_METHOD)
abstract class TransportTest {

    protected val clock: Clock = mockk()
    protected val crypto = mockk<Crypto>()
    protected val settingsManager = mockk<SettingsManager>()
    protected val metadataManager = mockk<MetadataManager>()
    protected val context = mockk<Context>(relaxed = true)

    protected val sigInfo: SigningInfo = mockk()
    protected val token = Random.nextLong()
    protected val packageInfo = PackageInfo().apply {
        packageName = "org.example"
        longVersionCode = Random.nextLong()
        applicationInfo = ApplicationInfo().apply {
            flags = FLAG_ALLOW_BACKUP or FLAG_INSTALLED
        }
        signingInfo = sigInfo
    }
    protected val pmPackageInfo = PackageInfo().apply {
        packageName = MAGIC_PACKAGE_MANAGER
    }

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
