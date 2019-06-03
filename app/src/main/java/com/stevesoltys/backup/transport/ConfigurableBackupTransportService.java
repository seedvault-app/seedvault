package com.stevesoltys.backup.transport;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransportService extends Service {

    private static final int FOREGROUND_ID = 43594;

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
        startForeground(FOREGROUND_ID, new Notification.Builder(this).build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return getBackupTransport().getBinder();
    }
}
