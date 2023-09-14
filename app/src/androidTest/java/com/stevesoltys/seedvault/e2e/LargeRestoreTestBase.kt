package com.stevesoltys.seedvault.e2e

import com.stevesoltys.seedvault.e2e.screen.impl.RecoveryCodeScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RestoreScreen
import com.stevesoltys.seedvault.restore.RestoreViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal interface LargeRestoreTestBase : LargeTestBase {

    companion object {
        private const val RESTORE_TIMEOUT = 360 * 1000L
    }

    val spyRestoreViewModel: RestoreViewModel

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

    fun performRestore() {
        RestoreScreen {
            backupListItem.clickAndWaitForNewWindow()
            waitUntilIdle()

            waitForInstallResult()
            nextButton.clickAndWaitForNewWindow()

            waitForRestoreDataResult()
            finishButton.clickAndWaitForNewWindow()
            skipButton.clickAndWaitForNewWindow()
            waitUntilIdle()
        }
    }

    private fun waitForInstallResult() = runBlocking {
        withTimeout(RESTORE_TIMEOUT) {
            while (spyRestoreViewModel.installResult.value == null ||
                spyRestoreViewModel.nextButtonEnabled.value == false
            ) {
                delay(100)
            }
        }

        val restoreResultValue = spyRestoreViewModel.installResult.value
            ?: error("Restore APKs timed out")

        assert(!restoreResultValue.hasFailed) { "Failed to install packages" }
        waitUntilIdle()
    }

    private fun waitForRestoreDataResult() = runBlocking {
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
