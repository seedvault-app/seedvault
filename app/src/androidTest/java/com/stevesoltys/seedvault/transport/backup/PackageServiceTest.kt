package com.stevesoltys.seedvault.transport.backup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.KoinComponent
import org.koin.core.inject

@RunWith(AndroidJUnit4::class)
class PackageServiceTest : KoinComponent {

    private val packageService: PackageService by inject()

    @Test
    fun testNotAllowedPackages() {
        val packages = packageService.notAllowedPackages
        assertTrue(packages.isNotEmpty())
        Log.e("TEST", "Packages: $packages")
    }

}
