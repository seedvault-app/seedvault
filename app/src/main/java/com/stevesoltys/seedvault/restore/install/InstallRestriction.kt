/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.os.UserManager
import android.os.UserManager.DISALLOW_INSTALL_APPS
import android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
import android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY

internal fun interface InstallRestriction {
    fun isAllowedToInstallApks(): Boolean
}

private fun UserManager.isDisallowed(restriction: String): Boolean {
    return userRestrictions.getBoolean(restriction, false)
}

internal fun UserManager.isAllowedToInstallApks(): Boolean {
    // install isn't allowed if one of those user restrictions is set
    val disallowed = isDisallowed(DISALLOW_INSTALL_APPS) ||
        isDisallowed(DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
        isDisallowed(DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    // install is allowed, if it isn't disallowed
    return !disallowed
}
