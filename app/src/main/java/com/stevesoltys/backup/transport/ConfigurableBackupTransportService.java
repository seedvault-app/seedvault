package com.stevesoltys.backup.transport;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransportService extends Service {

    private static final String TAG = ConfigurableBackupTransportService.class.getName();

    private static ConfigurableBackupTransport backupTransport = null;

    public static ConfigurableBackupTransport getBackupTransport() {

        if (backupTransport == null) {
            backupTransport = new ConfigurableBackupTransport();
        }

        return backupTransport;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return getBackupTransport().getBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed.");
    }
}
