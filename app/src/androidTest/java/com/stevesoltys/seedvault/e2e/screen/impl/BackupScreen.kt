package com.stevesoltys.seedvault.e2e.screen.impl

import android.os.Build
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object BackupScreen : UiDeviceScreen<BackupScreen>() {

    val backupMenu = findObject { description("More options") }

    val backupNowButton = findObject { text("Backup now") }

    val backupStatusButton = findObject { text("Backup status") }

    val backupLocationButton = findObject { text("Backup location") }

    val backupSwitch = findObject { text("Backup my apps") }

    val internalStorageButton = findObject { textContains(Build.MODEL) }

    val useAnywayButton = findObject { text("USE ANYWAY") }

    val initializingText: BySelector = By.textContains("Initializing backup location")
}
