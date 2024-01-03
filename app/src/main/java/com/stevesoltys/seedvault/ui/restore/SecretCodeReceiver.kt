package com.stevesoltys.seedvault.ui.restore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = BroadcastReceiver::class.java.simpleName

        private const val RESTORE_SECRET_CODE = "7378673"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.host != RESTORE_SECRET_CODE) return
        Log.d(TAG, "Restore secret code received.")
        val i = Intent(context, RestoreActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(i)
    }
}
