/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.screen.impl

import android.widget.EditText
import com.stevesoltys.seedvault.e2e.screen.UiDeviceScreen

object DocumentPickerScreen : UiDeviceScreen<DocumentPickerScreen>() {

    val createNewFolderButton = findObject { text("CREATE NEW FOLDER") }

    val useThisFolderButton = findObject { text("USE THIS FOLDER") }

    val textBox = findObject { className(EditText::class.java) }

    val okButton = findObject { text("OK") }

    val allowButton = findObject { text("ALLOW") }

    fun existingFolder(folderName: String) = findObject { text(folderName) }
}
