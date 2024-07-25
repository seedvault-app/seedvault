/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core

import android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.UserManager

/**
 * Hack to allow other profiles access to USB backend.
 * @return the context of the device's main user, so use with great care!
 */
@Suppress("MissingPermission")
public fun Context.getBackendContext(isUsbStorage: () -> Boolean): Context {
    if (checkSelfPermission(INTERACT_ACROSS_USERS_FULL) == PERMISSION_GRANTED && isUsbStorage()) {
        UserManager.get(this).getProfileParent(user)
            ?.let { parent -> return createContextAsUser(parent, 0) }
    }
    return this
}
