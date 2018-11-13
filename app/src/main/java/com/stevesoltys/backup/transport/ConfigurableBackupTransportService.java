package com.stevesoltys.backup.transport;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransportService extends Service {

    private static ConfigurableBackupTransport backupTransport = null;

    public static ConfigurableBackupTransport getBackupTransport() {
        return backupTransport;
    }

    public void onCreate() {
        if (backupTransport == null) {
            backupTransport = new ConfigurableBackupTransport();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return backupTransport.getBinder();
    }
}
