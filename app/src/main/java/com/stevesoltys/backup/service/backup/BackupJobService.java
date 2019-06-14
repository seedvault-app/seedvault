package com.stevesoltys.backup.service.backup;

import android.app.backup.BackupManager;
import android.app.backup.IBackupManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;

import com.stevesoltys.backup.service.PackageService;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;

import static android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP;
import static android.os.ServiceManager.getService;

public class BackupJobService extends JobService {

    private final static String TAG = BackupJobService.class.getName();

    private final IBackupManager backupManager;
    private final PackageService packageService = new PackageService();

    public BackupJobService() {
        backupManager = IBackupManager.Stub.asInterface(getService("backup"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Triggering full backup");
        startService(new Intent(this, ConfigurableBackupTransportService.class));
        try {
            String[] packages = packageService.getEligiblePackages();
            // TODO use an observer to know when backups fail
            int result = backupManager.requestBackup(packages, null, null, FLAG_NON_INCREMENTAL_BACKUP);
            if (result == BackupManager.SUCCESS) {
                Log.i(TAG, "Backup succeeded ");
            } else {
                Log.e(TAG, "Backup failed: " + result);
            }

        // TODO show notification on backup error
        } catch (RemoteException e) {
            Log.e(TAG, "Error during backup: ", e);
        } finally {
            jobFinished(params, false);
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        try {
            backupManager.cancelBackups();
        } catch (RemoteException e) {
            Log.e(TAG, "Error cancelling backup: ", e);
        }
        return true;
    }

}
