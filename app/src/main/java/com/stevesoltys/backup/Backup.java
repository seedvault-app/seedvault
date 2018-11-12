package com.stevesoltys.backup;

import android.app.Application;
import android.content.Intent;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;

/**
 * @author Steve Soltys
 */
public class Backup extends Application {

    @Override
    public void onCreate() {
        startService(new Intent(this, ConfigurableBackupTransportService.class));
    }

    @Override
    public void onTerminate() {
        stopService(new Intent(this, ConfigurableBackupTransportService.class));
    }
}
