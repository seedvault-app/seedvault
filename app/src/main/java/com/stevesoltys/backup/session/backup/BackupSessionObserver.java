package com.stevesoltys.backup.session.backup;

import android.app.backup.BackupProgress;

/**
 * @author Steve Soltys
 */
public interface BackupSessionObserver {

    void backupPackageStarted(BackupSession backupSession, String packageName, BackupProgress backupProgress);

    void backupPackageCompleted(BackupSession backupSession, String packageName, BackupResult result);

    void backupSessionCompleted(BackupSession backupSession, BackupResult result);
}
