package com.stevesoltys.seedvault.settings

import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SettingsManagerTest : TransportTest() {

    private val userExcludedApps = mutableSetOf<String>()

    @Test
    fun `isAppAllowedForBackup() exempts plugin provider and blacklisted apps`() {
        val pkgName = packageInfo.packageName

        preparePartiallyMockedSettingsManager()

        settingsManager.pluginProviderPackageName = pkgName
        assertFalse(settingsManager.isAppAllowedForBackup(pkgName),
            "Plugin provider must not be allowed for backup")

        settingsManager.pluginProviderPackageName = "new.package"
        userExcludedApps.add(pkgName)
        assertFalse(settingsManager.isAppAllowedForBackup(pkgName),
            "Excluded package must not be allowed for backup")

        userExcludedApps.remove(pkgName)
        assertTrue(settingsManager.isAppAllowedForBackup(pkgName),
            "Non-excluded package should be allowed if it is not the plugin provider")
    }

    private fun preparePartiallyMockedSettingsManager() {
        // Use our own app exclusion set to avoid the uninitialized set in mocked SettingsManager.
        every { settingsManager.blacklistedApps } answers { userExcludedApps }

        // Always call isAppAllowedForBackup unmocked rather than an unimplemented mock.
        every { settingsManager.isAppAllowedForBackup(any()) } answers { callOriginal() }

        // Always call isBackupEnabled unmocked.
        every { settingsManager.isBackupEnabled(any()) } answers { callOriginal() }

        // Always set the underlying provider name field for use by unmocked isAppAllowedForBackup.
        every {
            settingsManager.pluginProviderPackageName = any()
        } propertyType String::class answers {
            fieldValue = value
        }
    }

}
