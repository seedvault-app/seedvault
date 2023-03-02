/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.content.Context
import android.os.Build
import com.android.internal.app.LocalePicker
import com.stevesoltys.seedvault.R

class DeviceInfo(context: Context) {
    val densityDpi: Int = context.resources.displayMetrics.densityDpi
    val supportedABIs: List<String> = Build.SUPPORTED_ABIS.toList()
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val languages = LocalePicker.getSupportedLocales(context)
        .map { it.substringBefore('-') }
        .toSet()
    private val unknownSplitsOnlySameDevice =
        context.resources.getBoolean(R.bool.re_install_unknown_splits_only_on_same_device)

    fun areUnknownSplitsAllowed(deviceName: String): Boolean {
        return !unknownSplitsOnlySameDevice || this.deviceName == deviceName
    }

    fun isSameDevice(deviceName: String): Boolean {
        return this.deviceName == deviceName
    }

    fun isSupportedLanguage(name: String): Boolean = languages.contains(name)
}
