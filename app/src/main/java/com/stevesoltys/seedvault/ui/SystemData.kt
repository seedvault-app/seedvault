/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.stevesoltys.seedvault.R

internal const val PACKAGE_NAME_SMS = "com.android.providers.telephony"
internal const val PACKAGE_NAME_SETTINGS = "com.android.providers.settings"
internal const val PACKAGE_NAME_CALL_LOG = "com.android.calllogbackup"
internal const val PACKAGE_NAME_CONTACTS = "org.calyxos.backup.contacts"
internal const val PACKAGE_NAME_SYSTEM = "@org.calyxos.system@"

val systemData = mapOf(
    PACKAGE_NAME_SMS to SystemData(R.string.backup_sms, R.drawable.ic_message),
    PACKAGE_NAME_SETTINGS to SystemData(R.string.backup_settings, R.drawable.ic_settings),
    PACKAGE_NAME_CALL_LOG to SystemData(R.string.backup_call_log, R.drawable.ic_call),
    PACKAGE_NAME_CONTACTS to SystemData(R.string.backup_contacts, R.drawable.ic_contacts),
)

data class SystemData(
    @StringRes val nameRes: Int,
    @DrawableRes val iconRes: Int,
)
