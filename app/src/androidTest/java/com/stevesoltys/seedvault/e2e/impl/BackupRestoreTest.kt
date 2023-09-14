package com.stevesoltys.seedvault.e2e.impl

import androidx.test.filters.LargeTest
import com.stevesoltys.seedvault.e2e.SeedvaultLargeTest
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import org.junit.Test
import org.koin.core.component.inject

@LargeTest
internal class BackupRestoreTest : SeedvaultLargeTest() {

    private val packageService: PackageService by inject()

    private val settingsManager: SettingsManager by inject()

    @Test
    fun `backup and restore applications`() {
        launchBackupActivity()

        if (settingsManager.getStorage() == null) {
            confirmCode()
            chooseStorageLocation()
        }

        val eligiblePackages = getEligibleApps()
        performBackup(eligiblePackages)
        uninstallPackages(eligiblePackages)

        launchRestoreActivity()
        performRestore()

        // TODO: Get some real assertions in here..
        // val packagesAfterRestore = getEligibleApps()
        // assert(eligiblePackages == packagesAfterRestore)
    }

    private fun getEligibleApps() = packageService.userApps
        .map { it.packageName }.toSet()

}
