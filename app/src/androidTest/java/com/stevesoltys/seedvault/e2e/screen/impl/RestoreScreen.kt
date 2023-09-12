package com.stevesoltys.seedvault.e2e.screen.impl

import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object RestoreScreen : UiDeviceScreen<RestoreScreen>() {

    val backupListItem = findObject { textContains("Last backup 0 hr") }

    val nextButton = findObject { text("Next") }

    val finishButton = findObject { text("Finish") }
}
