package com.stevesoltys.seedvault.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.stevesoltys.seedvault.e2e.screen.impl.BackupScreen
import com.stevesoltys.seedvault.e2e.screen.impl.DocumentPickerScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RecoveryCodeScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RestoreScreen
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import java.lang.Thread.sleep

@RunWith(AndroidJUnit4::class)
abstract class LargeTestBase : TestCase(), KoinComponent {

    @Before
    open fun setUp() {
        // reset document picker state, and delete old backups
        runCommand("pm clear com.google.android.documentsui")
        runCommand("rm -Rf /sdcard/seedvault")
    }

    @After
    open fun tearDown() {
        screenshot("end")
    }

    protected fun launchBackupActivity() = run {
        runCommand("am start -n ${device.targetContext.packageName}/.settings.SettingsActivity")
        waitUntilIdle()
    }

    protected fun launchRestoreActivity() = run {
        runCommand("am start -n ${device.targetContext.packageName}/.restore.RestoreActivity")
        waitUntilIdle()
    }

    protected fun waitUntilIdle() {
        device.uiDevice.waitForIdle()
        sleep(3000)
    }

    protected fun verifyCode() = run {
        RecoveryCodeScreen {
            step("Confirm code") {
                screenshot("confirm code")
                confirmCodeButton.click()
            }
            step("Verify code") {
                screenshot("verify code")
                verifyCodeButton.scrollTo()
                verifyCodeButton.click()
            }
        }
    }

    protected fun chooseBackupLocation() = run {
        step("Choose backup location") {
            waitUntilIdle()
            screenshot("choose backup location")

            DocumentPickerScreen {
                createNewFolderButton.click()
                textBox.text = "seedvault"
                okButton.click()
                useThisFolderButton.click()
                allowButton.click()
            }
        }
    }

    protected fun startBackup() = run {
        launchBackupActivity()

        step("Run backup") {
            BackupScreen {
                backupMenu.clickAndWaitForNewWindow()
                backupNowButton.clickAndWaitForNewWindow()
                backupStatusButton.clickAndWaitForNewWindow()
            }
        }
    }

    protected fun startRestore() = run {
        launchRestoreActivity()

        step("Restore backup") {
            RestoreScreen {
                backupListItem.clickAndWaitForNewWindow()
            }
        }
    }

    protected fun uninstallPackages(packages: Set<String>) {
        packages.forEach { runCommand("pm uninstall $it") }
    }

    protected fun runCommand(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
    }

    protected fun screenshot(name: String) {
        device.screenshots.take(name.replace(" ", "_"))
    }
}
