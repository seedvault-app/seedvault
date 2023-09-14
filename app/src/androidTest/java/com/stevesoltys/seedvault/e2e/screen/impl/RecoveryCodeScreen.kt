package com.stevesoltys.seedvault.e2e.screen.impl

import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object RecoveryCodeScreen : UiDeviceScreen<RecoveryCodeScreen>() {

    val confirmCodeButton = findObject { text("Confirm code") }

    val verifyCodeButton = findObject { text("Verify") }

    fun wordTextField(index: Int) = findObject { text("Word ${index + 1}") }
}
