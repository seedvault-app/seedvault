package com.stevesoltys.backup;

import android.app.Application;
import android.content.Intent;

import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;

/**
 * @author Steve Soltys
 */
public class Backup extends Application {

    public static final int JOB_ID_BACKGROUND_BACKUP = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        startForegroundService(new Intent(this, ConfigurableBackupTransportService.class));
    }
}
