package com.stevesoltys.seedvault.e2e.screen.impl

import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object BackupScreen : UiDeviceScreen<BackupScreen>() {

    val backupMenu = findObject { description("More options") }

    val backupNowButton = findObject { text("Backup now") }

    val backupStatusButton = findObject { text("Backup status") }

    val backupLocationButton = findObject { text("Backup location") }
}
