/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

import android.content.Context
import com.stevesoltys.seedvault.R

enum class AppBackupState {
    IN_PROGRESS,
    SUCCEEDED,
    NOT_YET_BACKED_UP,
    FAILED,
    FAILED_NO_DATA,
    FAILED_WAS_STOPPED,
    FAILED_NOT_ALLOWED,
    FAILED_QUOTA_EXCEEDED,
    FAILED_NOT_INSTALLED;

    private val notShownString = "Please report a bug after you read this."

    fun getBackupText(context: Context): String = when (this) {
        IN_PROGRESS -> notShownString
        SUCCEEDED -> notShownString
        NOT_YET_BACKED_UP -> context.getString(R.string.backup_app_not_yet_backed_up)
        FAILED -> notShownString
        FAILED_NO_DATA -> context.getString(R.string.backup_app_no_data)
        FAILED_WAS_STOPPED -> context.getString(R.string.backup_app_was_stopped)
        FAILED_NOT_ALLOWED -> context.getString(R.string.restore_app_not_allowed)
        FAILED_NOT_INSTALLED -> context.getString(R.string.restore_app_not_installed)
        FAILED_QUOTA_EXCEEDED -> context.getString(R.string.backup_app_quota_exceeded)
    }

    fun getRestoreText(context: Context): String = when (this) {
        IN_PROGRESS -> notShownString
        SUCCEEDED -> notShownString
        NOT_YET_BACKED_UP -> context.getString(R.string.restore_app_not_yet_backed_up)
        FAILED -> notShownString
        FAILED_NO_DATA -> context.getString(R.string.backup_app_no_data)
        FAILED_WAS_STOPPED -> context.getString(R.string.restore_app_was_stopped)
        FAILED_NOT_ALLOWED -> context.getString(R.string.restore_app_not_allowed)
        FAILED_NOT_INSTALLED -> context.getString(R.string.restore_app_not_installed)
        FAILED_QUOTA_EXCEEDED -> context.getString(R.string.restore_app_quota_exceeded)
    }

}
