package com.stevesoltys.seedvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.util.Log
import com.stevesoltys.seedvault.restore.RestoreActivity

private val TAG = BroadcastReceiver::class.java.simpleName
private val RESTORE_SECRET_CODE = "7378673"

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val host = intent.data.host
        if (!RESTORE_SECRET_CODE.equals(host)) return
        Log.d(TAG, "Restore secret code received.")
        val i = Intent(context, RestoreActivity::class.java).apply {
            flags = FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(i)
    }
}
