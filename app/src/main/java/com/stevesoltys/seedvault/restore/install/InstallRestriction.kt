/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.os.UserManager

internal fun interface InstallRestriction {
    fun isAllowedToInstallApks(): Boolean
}

private fun UserManager.isRestricted(restriction: String): Boolean {
    return userRestrictions.getBoolean(restriction, false)
}

internal fun UserManager.isAllowedToInstallApks(): Boolean {
    return isRestricted(UserManager.DISALLOW_INSTALL_APPS) ||
        isRestricted(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
        isRestricted(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
}
