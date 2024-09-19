/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.content.pm.PackageInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.settings.AppStatus
import com.stevesoltys.seedvault.settings.SettingsManager
import io.mockk.every
import io.mockk.mockk
import org.calyxos.seedvault.core.backends.Backend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
@MediumTest
class PackageServiceTest : KoinComponent {

    private val packageService: PackageService by inject()

    private val settingsManager: SettingsManager by inject()

    private val backendManager: BackendManager by inject()

    private val backend: Backend get() = backendManager.backend

    @Test
    fun testNotAllowedPackages() {
        val packages = packageService.notBackedUpPackages
        Log.e("TEST", "Packages: $packages")
    }

    @Test
    fun `shouldIncludeAppInBackup exempts plugin provider and blacklisted apps`() {
        val packageInfo = PackageInfo().apply {
            packageName = "com.example"
        }

        val disabledAppStatus = mockk<AppStatus>().apply {
            every { packageName } returns packageInfo.packageName
            every { enabled } returns false
        }
        settingsManager.onAppBackupStatusChanged(disabledAppStatus)

        // Should not backup blacklisted apps
        assertFalse(packageService.shouldIncludeAppInBackup(packageInfo.packageName))

        val enabledAppStatus = mockk<AppStatus>().apply {
            every { packageName } returns packageInfo.packageName
            every { enabled } returns true
        }
        settingsManager.onAppBackupStatusChanged(enabledAppStatus)

        // Should backup non-blacklisted apps
        assertTrue(packageService.shouldIncludeAppInBackup(packageInfo.packageName))

        // Should not backup storage provider
        assertFalse(packageService.shouldIncludeAppInBackup(backend.providerPackageName!!))
    }
}
