package com.stevesoltys.seedvault.service.app

import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY
import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_ID
import android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME
import android.app.backup.IBackupManagerMonitor
import android.os.Bundle
import android.util.Log
import android.util.Log.DEBUG

private val TAG = BackupManagerOperationMonitor::class.java.name

class BackupManagerOperationMonitor : IBackupManagerMonitor.Stub() {

    override fun onEvent(bundle: Bundle) {
        if (!Log.isLoggable(TAG, DEBUG)) return
        Log.d(TAG, "ID: " + bundle.getInt(EXTRA_LOG_EVENT_ID))
        Log.d(TAG, "CATEGORY: " + bundle.getInt(EXTRA_LOG_EVENT_CATEGORY, -1))
        Log.d(TAG, "PACKAGE: " + bundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME, "?"))
    }
}
