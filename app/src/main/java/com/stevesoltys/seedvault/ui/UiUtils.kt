/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

import android.content.Context
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.getRelativeTimeSpanString
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener
import androidx.core.view.WindowCompat.setDecorFitsSystemWindows
import androidx.core.view.WindowInsetsCompat.CONSUMED
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import com.stevesoltys.seedvault.R

fun AppCompatActivity.setupEdgeToEdge() {
    val rootView = window.decorView.rootView
    setDecorFitsSystemWindows(window, false)
    setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
        val insets = windowInsets.getInsets(systemBars() or ime() or displayCutout())
        v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        CONSUMED
    }
}

fun Long.toRelativeTime(context: Context): CharSequence {
    return if (this == 0L || this == -1L) {
        context.getString(R.string.settings_backup_last_backup_never)
    } else {
        val now = System.currentTimeMillis()
        getRelativeTimeSpanString(this, now, MINUTE_IN_MILLIS, 0)
    }
}
