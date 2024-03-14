package com.stevesoltys.seedvault

import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY
import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_ID
import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME
import android.app.backup.BackupManagerMonitor.EXTRA_LOG_PREFLIGHT_ERROR
import android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_ERROR_PREFLIGHT
import android.app.backup.IBackupManagerMonitor
import android.os.Bundle
import android.util.Log
import android.util.Log.DEBUG

private val TAG = BackupMonitor::class.java.name

class BackupMonitor : IBackupManagerMonitor.Stub() {

    override fun onEvent(bundle: Bundle) {
        val id = bundle.getInt(EXTRA_LOG_EVENT_ID)
        val packageName = bundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME, "?")
        if (id == LOG_EVENT_ID_ERROR_PREFLIGHT) {
            val preflightResult = bundle.getLong(EXTRA_LOG_PREFLIGHT_ERROR, -1)
            Log.w(TAG, "Pre-flight error from $packageName: $preflightResult")
        }
        if (!Log.isLoggable(TAG, DEBUG)) return
        Log.d(TAG, "ID: $id")
        Log.d(TAG, "CATEGORY: " + bundle.getInt(EXTRA_LOG_EVENT_CATEGORY, -1))
        Log.d(TAG, "PACKAGE: $packageName")
    }

}
