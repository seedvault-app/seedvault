/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
internal abstract class SeedvaultLargeTest :
    LargeBackupTestBase, LargeRestoreTestBase, KoinComponent {

    @JvmField
    @Rule
    var name = TestName()

    companion object {
        private const val BASELINE_BACKUP_FOLDER = "seedvault_baseline"
        private const val RECOVERY_CODE_FILE = "recovery-code.txt"
    }

    private val baselineBackupFolderPath get() = "$externalStorageDir/$BASELINE_BACKUP_FOLDER"

    private val baselineBackupPath get() = "$baselineBackupFolderPath/.SeedVaultAndroidBackup"

    private val baselineRecoveryCodePath = "$baselineBackupFolderPath/$RECOVERY_CODE_FILE"

    private val keepRecordingScreen = AtomicBoolean(true)

    @Before
    open fun setUp() = runBlocking {
        resetApplicationState()
        clearTestBackups()

        runCommand("bmgr enable true")
        sleep(60_000)
        runCommand("bmgr transport com.stevesoltys.seedvault.transport.ConfigurableBackupTransport")
        sleep(60_000)

        startRecordingTest(keepRecordingScreen, name.methodName)
        restoreBaselineBackup()

        val arguments = InstrumentationRegistry.getArguments()

        if (arguments.getString("d2d_backup_test") == "true") {
            println("Enabling D2D backups for test")
            settingsManager.setD2dBackupsEnabled(true)

        } else {
            println("Disabling D2D backups for test")
            settingsManager.setD2dBackupsEnabled(false)
        }
    }

    @After
    open fun tearDown() {
        stopRecordingTest(keepRecordingScreen, name.methodName)
    }

    /**
     * Restore the baseline backup, if it exists.
     *
     * This is a hand-crafted backup containing various apps and app data that we use for
     * provisioning tests: https://github.com/seedvault-app/seedvault-test-data
     */
    private fun restoreBaselineBackup() {
        val backupFile = File(baselineBackupPath)

        val manageDocumentsPermission =
            targetContext.checkSelfPermission("android.permission.MANAGE_DOCUMENTS")

        if (manageDocumentsPermission == PackageManager.PERMISSION_GRANTED) {
            val extDir = externalStorageDir

            device.executeShellCommand("rm -R $extDir/.SeedVaultAndroidBackup")
            device.executeShellCommand(
                "cp -R $extDir/$BASELINE_BACKUP_FOLDER/" +
                    ".SeedVaultAndroidBackup $extDir"
            )
            device.executeShellCommand(
                "cp -R $extDir/$BASELINE_BACKUP_FOLDER/" +
                    "recovery-code.txt $extDir"
            )
        }

        if (backupFile.exists()) {
            launchRestoreActivity()
            chooseStorageLocation(folderName = BASELINE_BACKUP_FOLDER, exists = true)
            typeInRestoreCode(baselineBackupRecoveryCode())
            performRestore()

            resetApplicationState()
        }
    }

    private fun baselineBackupRecoveryCode(): List<String> {
        val recoveryCodeFile = File(baselineRecoveryCodePath)

        return recoveryCodeFile.readLines()
            .filter { it.isNotBlank() }
            .joinToString(separator = " ") { it.trim() }
            .split(" ")
    }
}
