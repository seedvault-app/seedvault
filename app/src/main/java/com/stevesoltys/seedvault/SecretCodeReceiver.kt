/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.util.Log
import com.stevesoltys.seedvault.restore.RestoreActivity

private val TAG = BroadcastReceiver::class.java.simpleName
private const val RESTORE_SECRET_CODE = "7378673"

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.host != RESTORE_SECRET_CODE) return
        Log.d(TAG, "Restore secret code received.")
        val i = Intent(context, RestoreActivity::class.java).apply {
            flags = FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(i)
    }
}
