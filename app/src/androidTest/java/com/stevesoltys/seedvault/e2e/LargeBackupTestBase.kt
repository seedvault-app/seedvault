/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e

import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import androidx.test.uiautomator.Until
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
import kotlin.test.fail

internal interface LargeBackupTestBase : LargeTestBase {

    companion object {
        private const val BACKUP_TIMEOUT = 360 * 1000L
    }

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

                BackupScreen {
                    device.wait(Until.hasObject(initializingText), 10000)
                    device.wait(Until.gone(initializingText), 120000)
                }
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
            spyKVBackup.performBackup(any(), any(), any())
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
            val oldMap = HashMap<String, String>()
            // @pm@ and android can get backed up multiple times (if we need more than one request)
            // so we need to keep the data it backed up before
            if (backupResult.kv.containsKey(packageName)) {
                backupResult.kv[packageName]?.forEach { (key, value) ->
                    // if a key existing in new data, we use its value from new data, don't override
                    if (!data.containsKey(key)) oldMap[key] = value
                }
            }
            backupResult.kv[packageName!!] = data
                .mapValues { entry -> entry.value.sha256() }
                .toMutableMap()
                .apply {
                    putAll(oldMap)
                }

            packageName = null
            data = mutableMapOf()
            callOriginal()
        }
    }

    private fun spyOnFullBackupData(backupResult: SeedvaultLargeTestResult) {
        var packageName: String? = null
        var dataIntercept = ByteArrayOutputStream()

        coEvery {
            spyFullBackup.performFullBackup(any(), any(), any())
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

        coEvery {
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
            spyBackupNotificationManager.onBackupSuccess(any(), any(), any())
        } answers {
            val success = firstArg<Boolean>()
            assert(success) { "Backup failed." }

            callOriginal()
            completed.set(true)
        }
        every {
            spyBackupNotificationManager.onBackupError()
        } answers {
            callOriginal()
            completed.set(true)
            fail("Backup failed.")
        }

        return completed
    }
}
