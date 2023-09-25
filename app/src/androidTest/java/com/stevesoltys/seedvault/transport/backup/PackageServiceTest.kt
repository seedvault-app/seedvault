package com.stevesoltys.seedvault.transport.backup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
@MediumTest
class PackageServiceTest : KoinComponent {

    private val packageService: PackageService by inject()

    @Test
    fun testNotAllowedPackages() {
        val packages = packageService.notBackedUpPackages
        Log.e("TEST", "Packages: $packages")
    }

}
