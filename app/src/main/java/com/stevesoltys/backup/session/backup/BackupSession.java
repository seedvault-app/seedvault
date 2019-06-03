package com.stevesoltys.backup.session.backup;

import android.app.backup.BackupManager;
import android.app.backup.BackupProgress;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;

import java.util.Set;

import static android.app.backup.BackupManager.FLAG_NON_INCREMENTAL_BACKUP;

/**
 * @author Steve Soltys
 */
public class BackupSession extends IBackupObserver.Stub {

    private final IBackupManager backupManager;

    private final BackupSessionObserver backupSessionObserver;

    private final Set<String> packages;

    public BackupSession(IBackupManager backupManager, BackupSessionObserver backupSessionObserver,
                         Set<String> packages) {
        this.backupManager = backupManager;
        this.backupSessionObserver = backupSessionObserver;
        this.packages = packages;
    }

    public void start() throws RemoteException {
        String [] selectedPackageArray = packages.toArray(new String[packages.size()]);
        backupManager.requestBackup(selectedPackageArray, this, null, FLAG_NON_INCREMENTAL_BACKUP);
    }

    public void stop(BackupResult result) throws RemoteException {
        backupManager.cancelBackups();
        backupSessionObserver.backupSessionCompleted(this, result);
    }

    @Override
    public void onUpdate(String currentPackage, BackupProgress backupProgress) {
        backupSessionObserver.backupPackageStarted(this, currentPackage, backupProgress);
    }

    @Override
    public void onResult(String currentPackage, int status) {
        backupSessionObserver.backupPackageCompleted(this, currentPackage, getBackupResult(status));
    }

    @Override
    public void backupFinished(int status) {
        backupSessionObserver.backupSessionCompleted(this, getBackupResult(status));
    }

    private BackupResult getBackupResult(int status) {
        if (status == BackupManager.SUCCESS) {
            return BackupResult.SUCCESS;

        } else if (status == BackupManager.ERROR_BACKUP_CANCELLED) {
            return BackupResult.CANCELLED;

        } else {
            return BackupResult.FAILURE;
        }
    }
}
