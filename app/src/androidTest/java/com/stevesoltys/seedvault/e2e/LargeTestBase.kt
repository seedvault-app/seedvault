package com.stevesoltys.seedvault.e2e

import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Environment
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.stevesoltys.seedvault.crypto.ANDROID_KEY_STORE
import com.stevesoltys.seedvault.crypto.KEY_ALIAS_BACKUP
import com.stevesoltys.seedvault.crypto.KEY_ALIAS_MAIN
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.currentRestoreStorageViewModel
import com.stevesoltys.seedvault.currentRestoreViewModel
import com.stevesoltys.seedvault.e2e.screen.impl.BackupScreen
import com.stevesoltys.seedvault.e2e.screen.impl.DocumentPickerScreen
import com.stevesoltys.seedvault.e2e.screen.impl.RecoveryCodeScreen
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.lang.Thread.sleep
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

internal interface LargeTestBase : KoinComponent {

    companion object {
        private const val TEST_STORAGE_FOLDER = "seedvault_test"
        private const val TEST_VIDEO_FOLDER = "seedvault_test_videos"
    }

    val externalStorageDir: String get() = Environment.getExternalStorageDirectory().absolutePath

    val testStoragePath get() = "$externalStorageDir/$TEST_STORAGE_FOLDER"

    val testVideoPath get() = "$externalStorageDir/$TEST_VIDEO_FOLDER"

    val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val uiAutomation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    val packageService: PackageService get() = get()

    val settingsManager: SettingsManager get() = get()

    val keyManager: KeyManager get() = get()

    val documentsStorage: DocumentsStorage get() = get()

    val spyMetadataManager: MetadataManager get() = get()

    val spyRestoreViewModel: RestoreViewModel
        get() = currentRestoreViewModel ?: error("currentRestoreViewModel is null")

    val spyRestoreStorageViewModel: RestoreStorageViewModel
        get() = currentRestoreStorageViewModel ?: error("currentRestoreStorageViewModel is null")

    fun resetApplicationState() {
        settingsManager.setNewToken(null)
        documentsStorage.reset(null)

        val sharedPreferences = permitDiskReads {
            PreferenceManager.getDefaultSharedPreferences(targetContext)
        }
        sharedPreferences.edit().clear().apply()

        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }.apply {
            deleteEntry(KEY_ALIAS_MAIN)
            deleteEntry(KEY_ALIAS_BACKUP)
        }

        clearDocumentPickerAppData()
    }

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

        val folder = testVideoPath
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

    fun uninstallPackages(packages: Collection<PackageInfo>) {
        packages.forEach { runCommand("pm uninstall ${it.packageName}") }
    }

    fun clearDocumentPickerAppData() {
        runCommand("pm clear com.google.android.documentsui")
    }

    fun clearTestBackups() {
        File(testStoragePath).deleteRecursively()
    }

    fun changeBackupLocation(
        folderName: String = TEST_STORAGE_FOLDER,
        exists: Boolean = false,
    ) {
        BackupScreen {
            clearDocumentPickerAppData()
            backupLocationButton.clickAndWaitForNewWindow()

            chooseStorageLocation(folderName, exists)
        }
    }

    fun chooseStorageLocation(
        folderName: String = TEST_STORAGE_FOLDER,
        exists: Boolean = false,
    ) {
        val manageDocumentsPermission =
            targetContext.checkSelfPermission("android.permission.MANAGE_DOCUMENTS")

        if (manageDocumentsPermission != PERMISSION_GRANTED) {
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
        } else {
            val extDir = externalStorageDir

            device.executeShellCommand("rm -R $extDir/.SeedVaultAndroidBackup")
            device.executeShellCommand(
                "cp -R $extDir/$folderName/" +
                    ".SeedVaultAndroidBackup $extDir"
            )
            device.executeShellCommand("cp -R $extDir/$folderName/recovery-code.txt $extDir")

            BackupScreen {
                internalStorageButton.clickAndWaitForNewWindow()

                if (useAnywayButton.waitForExists(3000)) {
                    useAnywayButton.clickAndWaitForNewWindow()
                }
            }
        }

        BackupScreen {
            device.wait(Until.hasObject(initializingText), 10000)
            device.wait(Until.gone(initializingText), 120000)
        }
    }

    fun confirmCode() {
        RecoveryCodeScreen {
            confirmCodeButton.click()

            verifyCodeButton.scrollTo().click()
        }
    }

    fun ByteArray.sha256(): String {
        val data = MessageDigest.getInstance("SHA-256").digest(this)

        return data.joinToString("") { "%02x".format(it) }
    }
}
