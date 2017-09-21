package com.stevesoltys.backup.transport;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransportService extends Service {

    // TODO: Make this field non-static and communicate with this service correctly.
    private static ConfigurableBackupTransport backupTransport;

    public ConfigurableBackupTransportService() {
        backupTransport = null;
    }

    @Override
    public void onCreate() {
        if (backupTransport == null) {
            backupTransport = new ConfigurableBackupTransport();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return backupTransport.getBinder();
    }

    public static ConfigurableBackupTransport getBackupTransport() {
        return backupTransport;
    }
}
