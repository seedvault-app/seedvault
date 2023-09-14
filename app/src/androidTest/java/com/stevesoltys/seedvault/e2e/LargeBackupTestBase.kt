package com.stevesoltys.seedvault.e2e

import com.stevesoltys.seedvault.e2e.screen.impl.BackupScreen
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.clearMocks
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal interface LargeBackupTestBase : LargeTestBase {

    companion object {
        private const val BACKUP_TIMEOUT = 360 * 1000L
    }

    val spyBackupNotificationManager: BackupNotificationManager

    fun launchBackupActivity() {
        runCommand("am start -n ${targetContext.packageName}/.settings.SettingsActivity")
        waitUntilIdle()
    }

    fun startBackup() {
        BackupScreen {
            backupMenu.clickAndWaitForNewWindow()
            waitUntilIdle()

            backupNowButton.clickAndWaitForNewWindow()
            waitUntilIdle()

            backupStatusButton.clickAndWaitForNewWindow()
            waitUntilIdle()
        }
    }

    fun performBackup(expectedPackages: Set<String>) {
        val backupResult = spyOnBackup(expectedPackages)
        startBackup()
        waitForBackupResult(backupResult)
    }

    private fun spyOnBackup(expectedPackages: Set<String>): AtomicBoolean {
        val finishedBackup = AtomicBoolean(false)

        clearMocks(spyBackupNotificationManager)

        every {
            spyBackupNotificationManager.onBackupFinished(any(), any())
        } answers {
            val success = firstArg<Boolean>()
            assert(success) { "Backup failed." }

            this.callOriginal()
            finishedBackup.set(true)
        }

        return finishedBackup
    }

    private fun waitForBackupResult(finishedBackup: AtomicBoolean) {
        runBlocking {
            withTimeout(BACKUP_TIMEOUT) {
                while (!finishedBackup.get()) {
                    delay(100)
                }
            }
        }
    }
}
