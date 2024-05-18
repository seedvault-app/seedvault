/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.screen.impl

import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object RestoreScreen : UiDeviceScreen<RestoreScreen>() {

    val backupListItem = findObject { textContains("Last backup") }

    val nextButton = findObject { text("Next") }

    val finishButton = findObject { text("Finish") }

    val skipButton = findObject { text("Skip restoring files") }

    val someAppsNotInstalledText = findObject { textContains("Some apps") }

    val someAppsNotRestoredText = findObject { textContains("some apps") }
}
