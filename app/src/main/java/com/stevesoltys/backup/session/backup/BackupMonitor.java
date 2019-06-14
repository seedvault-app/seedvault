package com.stevesoltys.backup.session.backup;

import android.app.backup.IBackupManagerMonitor;
import android.os.Bundle;
import android.util.Log;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_ID;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;

class BackupMonitor extends IBackupManagerMonitor.Stub {

    @Override
    public void onEvent(Bundle bundle) {
        Log.d("BackupMonitor", "ID: " + bundle.getInt(EXTRA_LOG_EVENT_ID));
        Log.d("BackupMonitor", "CATEGORY: " + bundle.getInt(EXTRA_LOG_EVENT_CATEGORY, -1));
        Log.d("BackupMonitor", "PACKAGE: " + bundle.getString(EXTRA_LOG_EVENT_PACKAGE_NAME, "?"));
    }

}
