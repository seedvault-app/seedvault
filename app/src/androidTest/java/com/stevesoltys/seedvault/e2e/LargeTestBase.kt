package com.stevesoltys.seedvault.e2e

import android.app.UiAutomation
import android.content.Context
import android.os.Environment
import androidx.annotation.WorkerThread
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.stevesoltys.seedvault.e2e.screen.impl.DocumentPickerScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RecoveryCodeScreen
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.Thread.sleep
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

interface LargeTestBase {

    companion object {
        private const val TEST_STORAGE_FOLDER = "seedvault_test"
        private const val TEST_VIDEO_FOLDER = "seedvault_test_videos"
    }

    fun externalStorageDir(): String = Environment.getExternalStorageDirectory().absolutePath

    fun testStoragePath(): String = "${externalStorageDir()}/$TEST_STORAGE_FOLDER"

    fun testVideoPath(): String = "${externalStorageDir()}/$TEST_VIDEO_FOLDER"

    val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val uiAutomation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun waitUntilIdle() {
        device.waitForIdle()
        sleep(3000)
    }

    fun runCommand(command: String) {
        uiAutomation.executeShellCommand(command).close()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @WorkerThread
    suspend fun startScreenRecord(
        keepRecordingScreen: AtomicBoolean,
        testName: String,
    ) {
        val simpleDateFormat = SimpleDateFormat("yyyyMMdd_hhmmss")
        val timeStamp = simpleDateFormat.format(Calendar.getInstance().time)
        val fileName = "${timeStamp}_${testName.replace(" ", "_")}"

        val folder = testVideoPath()

        runCommand("mkdir -p $folder")

        // screen record automatically stops after 3 minutes
        // we need to block on a loop and split it into multiple files
        GlobalScope.launch(Dispatchers.IO) {
            var index = 0

            while (keepRecordingScreen.get()) {
                device.executeShellCommand("screenrecord $folder/$fileName-${index++}.mp4")
            }
        }
    }

    @WorkerThread
    fun stopScreenRecord(keepRecordingScreen: AtomicBoolean) {
        keepRecordingScreen.set(false)

        runCommand("pkill -2 screenrecord")
    }

    fun uninstallPackages(packages: Set<String>) {
        packages.forEach { runCommand("pm uninstall $it") }
    }

    fun clearDocumentPickerAppData() {
        runCommand("pm clear com.google.android.documentsui")
    }

    fun clearTestBackups() {
        runCommand("rm -Rf ${testStoragePath()}")
    }

    fun chooseStorageLocation(
        folderName: String = TEST_STORAGE_FOLDER,
        exists: Boolean = false,
    ) {
        DocumentPickerScreen {
            if (exists) {
                existingFolder(folderName).scrollTo().clickAndWaitForNewWindow()

            } else {
                createNewFolderButton.clickAndWaitForNewWindow()
                textBox.text = folderName
                okButton.clickAndWaitForNewWindow()
            }

            useThisFolderButton.clickAndWaitForNewWindow()
            allowButton.clickAndWaitForNewWindow()
        }
    }

    fun confirmCode() {
        RecoveryCodeScreen {
            confirmCodeButton.click()

            verifyCodeButton.scrollTo().click()
        }
    }
}
