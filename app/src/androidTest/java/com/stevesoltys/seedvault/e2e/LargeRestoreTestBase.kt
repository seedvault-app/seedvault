/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e

import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.e2e.io.BackupDataOutputIntercept
import com.stevesoltys.seedvault.e2e.io.OutputStreamIntercept
import com.stevesoltys.seedvault.e2e.screen.impl.RecoveryCodeScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RestoreScreen
import com.stevesoltys.seedvault.transport.restore.FullRestore
import com.stevesoltys.seedvault.transport.restore.KVRestore
import com.stevesoltys.seedvault.transport.restore.OutputFactory
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.component.get
import java.io.ByteArrayOutputStream

internal interface LargeRestoreTestBase : LargeTestBase {

    companion object {
        private const val RESTORE_TIMEOUT = 360 * 1000L
    }

    val spyFullRestore: FullRestore get() = get()

    val spyKVRestore: KVRestore get() = get()

    val spyOutputFactory: OutputFactory get() = get()

    fun launchRestoreActivity() {
        runCommand("am start -n ${targetContext.packageName}/.restore.RestoreActivity")
        waitUntilIdle()
    }

    fun typeInRestoreCode(code: List<String>) {
        assert(code.size == 12) { "Code must have 12 words." }

        RecoveryCodeScreen {
            waitUntilIdle()

            code.forEachIndexed { index, word ->
                wordTextField(index).text = word
            }

            waitUntilIdle()
            verifyCodeButton.scrollTo().click()
        }
    }

    fun performRestore(): SeedvaultLargeTestResult {

        val result = SeedvaultLargeTestResult(
            full = mutableMapOf(),
            kv = mutableMapOf(),
            userApps = emptyList(), // will update everything below this after restore
            userNotAllowedApps = emptyList()
        )

        spyOnRestoreData(result)

        RestoreScreen {
            backupListItem.clickAndWaitForNewWindow()
            waitUntilIdle()

            waitForAppSelectionLoaded()
            // just tap next in app selection
            appsSelectedButton.clickAndWaitForNewWindow()

            waitForInstallResult()

            if (someAppsNotInstalledText.exists()) {
                device.pressBack()
            }

            nextButton.clickAndWaitForNewWindow()

            waitForRestoreDataResult()

            if (someAppsNotRestoredText.exists()) {
                device.pressBack()
            }

            finishButton.clickAndWaitForNewWindow()
            skipButton.clickAndWaitForNewWindow()
            waitUntilIdle()
        }

        return result.copy(
            userApps = packageService.userApps,
            userNotAllowedApps = packageService.userNotAllowedApps
        )
    }

    private fun spyOnRestoreData(result: SeedvaultLargeTestResult) {
        clearMocks(spyOutputFactory)

        spyOnFullRestoreData(result)
        spyOnKVRestoreData(result)
    }

    private fun waitForAppSelectionLoaded() = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(RESTORE_TIMEOUT) {
                while (spyRestoreViewModel.selectedApps.value?.apps?.isNotEmpty() != true) {
                    delay(100)
                }
            }
        }
        waitUntilIdle()
    }

    private fun waitForInstallResult() = runBlocking {

        withContext(Dispatchers.Main) {
            withTimeout(RESTORE_TIMEOUT) {
                while (spyRestoreViewModel.installResult.value?.isFinished != true) {
                    delay(100)
                }
            }

            val restoreResultValue = spyRestoreViewModel.installResult.value
                ?: error("Restore APKs timed out")

            // TODO: Fix this, with current test an app or two breaks on install with AOSP image.
            // Just need to update the test data to work with the AOSP image.
            // assert(!restoreResultValue.hasFailed) { "Failed to install packages" }
        }

        waitUntilIdle()
    }

    private fun waitForRestoreDataResult() = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(RESTORE_TIMEOUT) {
                while (spyRestoreViewModel.restoreBackupResult.value == null) {
                    delay(100)
                }
            }

            val restoreResultValue = spyRestoreViewModel.restoreBackupResult.value
                ?: error("Restore app data timed out")

            assert(!restoreResultValue.hasError()) {
                "Restore failed: ${restoreResultValue.errorMsg}"
            }

            waitUntilIdle()
        }
    }

    private fun spyOnKVRestoreData(restoreResult: SeedvaultLargeTestResult) {
        var packageName: String? = null

        clearMocks(spyKVRestore)

        coEvery {
            spyKVRestore.initializeState(any(), any(), any(), any(), any())
        } answers {
            packageName = arg<PackageInfo>(3).packageName
            restoreResult.kv[packageName!!] = mutableMapOf()
            callOriginal()
        }

        every {
            spyOutputFactory.getBackupDataOutput(any())
        } answers {
            val fd = firstArg<ParcelFileDescriptor>().fileDescriptor

            BackupDataOutputIntercept(fd) { key, value ->
                restoreResult.kv[packageName!!]!![key] = value.sha256()
            }
        }
    }

    private fun spyOnFullRestoreData(restoreResult: SeedvaultLargeTestResult) {
        var packageName: String? = null
        var dataIntercept = ByteArrayOutputStream()

        clearMocks(spyFullRestore)

        coEvery {
            spyFullRestore.initializeState(any(), any(), any(), any())
        } answers {
            packageName?.let {
                restoreResult.full[it] = dataIntercept.toByteArray().sha256()
            }

            packageName = arg<PackageInfo>(3).packageName
            dataIntercept = ByteArrayOutputStream()

            callOriginal()
        }

        every {
            spyOutputFactory.getOutputStream(any())
        } answers {
            OutputStreamIntercept(
                outputStream = callOriginal(),
                intercept = dataIntercept
            )
        }

        every {
            spyFullRestore.abortFullRestore()
        } answers {
            packageName = null
            dataIntercept = ByteArrayOutputStream()
            callOriginal()
        }

        every {
            spyFullRestore.finishRestore()
        } answers {
            restoreResult.full[packageName!!] = dataIntercept.toByteArray().sha256()

            packageName = null
            dataIntercept = ByteArrayOutputStream()
            callOriginal()
        }
    }
}
