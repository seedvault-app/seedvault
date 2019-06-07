package com.stevesoltys.backup.service.backup;

import android.app.backup.BackupManager;
import android.app.backup.IBackupManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.collect.Sets;
import com.stevesoltys.backup.service.PackageService;
import com.stevesoltys.backup.service.TransportService;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import static android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP;
import static android.os.ServiceManager.getService;
import static com.stevesoltys.backup.activity.MainActivityController.createBackupFile;
import static com.stevesoltys.backup.settings.SettingsManager.getBackupFolderUri;
import static com.stevesoltys.backup.settings.SettingsManager.getBackupPassword;

public class BackupJobService extends JobService {

    private final static String TAG = BackupJobService.class.getName();

    private static final Set<String> IGNORED_PACKAGES = Sets.newArraySet(
            "com.android.providers.downloads.ui", "com.android.providers.downloads", "com.android.providers.media",
            "com.android.providers.calendar", "com.android.providers.contacts", "com.stevesoltys.backup"
    );

    private final IBackupManager backupManager;
    private final TransportService transportService = new TransportService();

    public BackupJobService() {
        backupManager = IBackupManager.Stub.asInterface(getService("backup"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Triggering full backup");
        try {
            LinkedList<String> packages = new LinkedList<>(new PackageService().getEligiblePackages());
            packages.removeAll(IGNORED_PACKAGES);
            Uri fileUri = createBackupFile(getContentResolver(), getBackupFolderUri(this));
            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder()
                    .setContext(this)
                    .setPackages(new HashSet<>(packages))
                    .setOutputUri(fileUri)
                    .setPassword(getBackupPassword(this))
                    .build();
            transportService.initializeBackupTransport(backupConfiguration);

            // TODO use an observer to know when backups fail
            String[] packageArray = packages.toArray(new String[packages.size()]);
            int result = backupManager.requestBackup(packageArray, null, null, FLAG_NON_INCREMENTAL_BACKUP);
            if (result == BackupManager.SUCCESS) {
                Log.i(TAG, "Backup succeeded ");
            } else {
                Log.e(TAG, "Backup failed: " + result);
            }

        // TODO show notification on backup error
        } catch (IOException e) {
            Log.e(TAG, "Error creating backup file: ", e);
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
