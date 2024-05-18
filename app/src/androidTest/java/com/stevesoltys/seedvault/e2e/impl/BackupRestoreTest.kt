/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.impl

import androidx.test.filters.LargeTest
import com.stevesoltys.seedvault.e2e.SeedvaultLargeTest
import com.stevesoltys.seedvault.e2e.SeedvaultLargeTestResult
import com.stevesoltys.seedvault.metadata.PackageState
import org.junit.Test

@LargeTest
internal class BackupRestoreTest : SeedvaultLargeTest() {

    @Test
    fun `backup and restore applications`() {
        launchBackupActivity()

        if (!keyManager.hasBackupKey()) {
            confirmCode()
        }

        if (settingsManager.getSafStorage() == null) {
            chooseStorageLocation()
        } else {
            changeBackupLocation()
        }

        val backupResult = performBackup()
        assertValidBackupMetadata(backupResult)

        uninstallPackages(backupResult.allUserApps())

        launchRestoreActivity()
        val restoreResult = performRestore()

        assertValidResults(backupResult, restoreResult)
    }

    private fun assertValidBackupMetadata(backup: SeedvaultLargeTestResult) {
        // Assert all user apps have metadata.
        backup.allUserApps().forEach { app ->
            assert(backup.backupResults.containsKey(app.packageName)) {
                "Metadata for $app missing from backup."
            }
        }

        // Assert all metadata has a valid state.
        backup.backupResults.forEach { (pkg, metadata) ->
            assert(metadata != null) { "Metadata for $pkg is null." }

            assert(metadata!!.state != PackageState.UNKNOWN_ERROR) {
                "Metadata for $pkg has an unknown state."
            }
        }
    }

    private fun assertValidResults(
        backup: SeedvaultLargeTestResult,
        restore: SeedvaultLargeTestResult,
    ) {
        assertAllUserAppsWereRestored(backup, restore)
        assertValidFullData(backup, restore)
        assertValidKeyValueData(backup, restore)
    }

    private fun assertAllUserAppsWereRestored(
        backup: SeedvaultLargeTestResult,
        restore: SeedvaultLargeTestResult,
    ) {
        val backupUserApps = backup.allUserApps()
            .map { it.packageName }.toSet()

        val restoreUserApps = restore.allUserApps()
            .map { it.packageName }.toSet()

        // Assert we re-installed all user apps.
        assert(restoreUserApps.containsAll(backupUserApps)) {
            val missingApps = backupUserApps
                .minus(restoreUserApps)
                .joinToString(", ")

            "Not all user apps were restored. Missing: $missingApps"
        }

        // Assert we restored data for all user apps that had successful backups.
        // This is expected to succeed because we are uninstalling the apps before restoring.
        val missingFromRestore = backup.userApps
            .map { it.packageName }
            .filter { backup.backupResults[it]?.state == PackageState.APK_AND_DATA }
            .filter { !restore.kv.containsKey(it) && !restore.full.containsKey(it) }

        if (missingFromRestore.isNotEmpty()) {
            val failedApps = missingFromRestore.joinToString(", ")

            error("Not all user apps had their data restored. Missing: $failedApps")
        }
    }

    private fun assertValidFullData(
        backup: SeedvaultLargeTestResult,
        restore: SeedvaultLargeTestResult,
    ) {
        // Assert all "full" restored data matches the backup data.
        val allUserPkgs = backup.allUserApps().map { it.packageName }

        restore.full.forEach { (pkg, fullData) ->
            if (allUserPkgs.contains(pkg)) {
                assert(backup.full.containsKey(pkg)) {
                    "Full data for $pkg missing from restore."
                }

                if (backup.backupResults[pkg]!!.state == PackageState.APK_AND_DATA) {
                    assert(fullData == backup.full[pkg]!!) {
                        "Full data for $pkg does not match."
                    }
                }
            }
        }
    }

    private fun assertValidKeyValueData(
        backup: SeedvaultLargeTestResult,
        restore: SeedvaultLargeTestResult,
    ) {
        // Assert all "key/value" restored data matches the backup data.
        restore.kv.forEach { (pkg, kvData) ->
            assert(backup.kv.containsKey(pkg)) {
                "KV data for $pkg missing from backup."
            }

            kvData.forEach { (key, value) ->
                assert(backup.kv[pkg]!!.containsKey(key)) {
                    "KV data for $pkg/$key exists in restore but is missing from backup."
                }

                assert(value.contentEquals(backup.kv[pkg]!![key]!!)) {
                    "KV data for $pkg/$key does not match."
                }
            }
        }
    }
}
