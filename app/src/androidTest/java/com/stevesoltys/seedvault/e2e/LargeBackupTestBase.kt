package com.stevesoltys.seedvault.e2e

import android.app.backup.IBackupManager
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.e2e.io.BackupDataInputIntercept
import com.stevesoltys.seedvault.e2e.io.InputStreamIntercept
import com.stevesoltys.seedvault.e2e.screen.impl.BackupScreen
import com.stevesoltys.seedvault.transport.backup.FullBackup
import com.stevesoltys.seedvault.transport.backup.InputFactory
import com.stevesoltys.seedvault.transport.backup.KVBackup
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.component.get
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal interface LargeBackupTestBase : LargeTestBase {

    companion object {
        private const val BACKUP_TIMEOUT = 360 * 1000L
    }

    val backupManager: IBackupManager get() = get()

    val spyBackupNotificationManager: BackupNotificationManager get() = get()

    val spyFullBackup: FullBackup get() = get()

    val spyKVBackup: KVBackup get() = get()

    val spyInputFactory: InputFactory get() = get()

    fun launchBackupActivity() {
        runCommand("am start -n ${targetContext.packageName}/.settings.SettingsActivity")
        waitUntilIdle()
    }

    fun startBackup() {
        BackupScreen {

            if (!backupManager.isBackupEnabled) {
                backupSwitch.click()
                waitUntilIdle()
            }

            backupMenu.clickAndWaitForNewWindow()
            waitUntilIdle()

            backupNowButton.clickAndWaitForNewWindow()
            waitUntilIdle()

            backupStatusButton.clickAndWaitForNewWindow()
            waitUntilIdle()
        }
    }

    fun performBackup(): SeedvaultLargeTestResult {

        val backupResult = SeedvaultLargeTestResult(
            full = mutableMapOf(),
            kv = mutableMapOf(),
            userApps = packageService.userApps,
            userNotAllowedApps = packageService.userNotAllowedApps
        )

        val completed = spyOnBackup(backupResult)
        startBackup()
        waitForBackupResult(completed)

        return backupResult.copy(
            backupResults = backupResult.allUserApps().associate {
                it.packageName to spyMetadataManager.getPackageMetadata(it.packageName)
            }.toMutableMap()
        )
    }

    private fun waitForBackupResult(completed: AtomicBoolean) {
        runBlocking {
            withTimeout(BACKUP_TIMEOUT) {
                while (!completed.get()) {
                    delay(100)
                }
            }
        }
    }

    private fun spyOnBackup(backupResult: SeedvaultLargeTestResult): AtomicBoolean {
        clearMocks(spyInputFactory, spyKVBackup, spyFullBackup)
        spyOnFullBackupData(backupResult)
        spyOnKVBackupData(backupResult)

        return spyOnBackupCompletion()
    }

    private fun spyOnKVBackupData(backupResult: SeedvaultLargeTestResult) {
        var packageName: String? = null
        var data = mutableMapOf<String, ByteArray>()

        coEvery {
            spyKVBackup.performBackup(any(), any(), any(), any(), any())
        } answers {
            packageName = firstArg<PackageInfo>().packageName
            callOriginal()
        }

        every {
            spyInputFactory.getBackupDataInput(any())
        } answers {
            val fd = firstArg<ParcelFileDescriptor>().fileDescriptor

            BackupDataInputIntercept(fd) { key, value ->
                data[key] = value
            }
        }

        coEvery {
            spyKVBackup.finishBackup()
        } answers {
            backupResult.kv[packageName!!] = data
                .mapValues { entry -> entry.value.sha256() }
                .toMutableMap()

            packageName = null
            data = mutableMapOf()
            callOriginal()
        }
    }

    private fun spyOnFullBackupData(backupResult: SeedvaultLargeTestResult) {
        var packageName: String? = null
        var dataIntercept = ByteArrayOutputStream()

        coEvery {
            spyFullBackup.performFullBackup(any(), any(), any(), any(), any())
        } answers {
            packageName = firstArg<PackageInfo>().packageName
            callOriginal()
        }

        every {
            spyInputFactory.getInputStream(any())
        } answers {
            InputStreamIntercept(
                inputStream = callOriginal(),
                intercept = dataIntercept
            )
        }

        every {
            spyFullBackup.finishBackup()
        } answers {
            val result = callOriginal()
            backupResult.full[packageName!!] = dataIntercept.toByteArray().sha256()

            packageName = null
            dataIntercept = ByteArrayOutputStream()
            result
        }
    }

    private fun spyOnBackupCompletion(): AtomicBoolean {
        val completed = AtomicBoolean(false)

        clearMocks(spyBackupNotificationManager)

        every {
            spyBackupNotificationManager.onBackupFinished(any(), any())
        } answers {
            val success = firstArg<Boolean>()
            assert(success) { "Backup failed." }

            callOriginal()
            completed.set(true)
        }

        return completed
    }
}
