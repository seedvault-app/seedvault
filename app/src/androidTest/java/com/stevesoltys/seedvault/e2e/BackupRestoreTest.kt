package com.stevesoltys.seedvault.e2e

import androidx.test.filters.LargeTest
import com.stevesoltys.seedvault.e2e.screen.impl.RestoreScreen
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean

@LargeTest
class BackupRestoreTest : LargeTestBase() {

    private val packageService: PackageService by inject()

    private val spyBackupNotificationManager: BackupNotificationManager by inject()

    private val restoreViewModel: RestoreViewModel by inject()

    companion object {
        private const val BACKUP_TIMEOUT = 360 * 1000L
        private const val RESTORE_TIMEOUT = 360 * 1000L
    }

    @Test
    fun `backup and restore applications`() = run {
        launchBackupActivity()
        verifyCode()
        chooseBackupLocation()

        val eligiblePackages = launchAllEligibleApps()
        performBackup(eligiblePackages)
        uninstallPackages(eligiblePackages)
        performRestore()

        val packagesAfterRestore = getEligibleApps()
        assert(eligiblePackages == packagesAfterRestore)
    }

    private fun getEligibleApps() = packageService.userApps
        .map { it.packageName }.toSet()

    private fun launchAllEligibleApps(): Set<String> {
        return getEligibleApps().onEach {
            val intent = device.targetContext.packageManager.getLaunchIntentForPackage(it)

            device.targetContext.startActivity(intent)
            waitUntilIdle()
        }
    }

    private fun performBackup(expectedPackages: Set<String>) = run {
        val backupResult = spyOnBackup(expectedPackages)
        startBackup()
        waitForBackupResult(backupResult)
        screenshot("backup result")
    }

    private fun spyOnBackup(expectedPackages: Set<String>): AtomicBoolean {
        val finishedBackup = AtomicBoolean(false)

        every {
            spyBackupNotificationManager.onBackupFinished(any(), any())
        } answers {
            val success = firstArg<Boolean>()
            assert(success) { "Backup failed." }

            val packageCount = secondArg<Int>()
            assert(packageCount == expectedPackages.size) {
                "Expected ${expectedPackages.size} apps, got $packageCount."
            }

            this.callOriginal()
            finishedBackup.set(true)
        }

        return finishedBackup
    }

    private fun waitForBackupResult(finishedBackup: AtomicBoolean) = run {
        step("Wait for backup completion") {
            runBlocking {
                withTimeout(BACKUP_TIMEOUT) {
                    while (!finishedBackup.get()) {
                        delay(100)
                    }
                }
            }
        }
    }

    private fun performRestore() = run {
        step("Start restore and await completion") {
            RestoreScreen {
                startRestore()
                waitForInstallResult()
                screenshot("restore app apks result")

                nextButton.click()
                waitForRestoreResult()
                screenshot("restore app data result")

                finishButton.click()
            }
        }
    }

    private fun waitForInstallResult() = runBlocking {
        withTimeout(RESTORE_TIMEOUT) {

            while (restoreViewModel.installResult.value == null) {
                delay(100)
            }

            val restoreResultValue = restoreViewModel.installResult.value!!
            assert(!restoreResultValue.hasFailed) { "Failed to install packages" }
        }
    }

    private fun waitForRestoreResult() = runBlocking {
        withTimeout(RESTORE_TIMEOUT) {

            while (restoreViewModel.restoreBackupResult.value == null) {
                delay(100)
            }

            val restoreResultValue = restoreViewModel.restoreBackupResult.value!!

            assert(!restoreResultValue.hasError()) {
                "Restore failed: ${restoreResultValue.errorMsg}"
            }
        }
    }
}
