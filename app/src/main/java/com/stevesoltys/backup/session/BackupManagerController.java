package com.stevesoltys.backup.session;

import android.app.backup.IBackupManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.stevesoltys.backup.session.backup.BackupSession;
import com.stevesoltys.backup.session.backup.BackupSessionObserver;
import com.stevesoltys.backup.session.restore.RestoreSession;
import com.stevesoltys.backup.session.restore.RestoreSessionObserver;

import java.util.ArrayList;
import java.util.List;

import static android.os.UserHandle.USER_SYSTEM;

/**
 * @author Steve Soltys
 */
public class BackupManagerController {

    private static final String BACKUP_TRANSPORT = "com.stevesoltys.backup.transport.ConfigurableBackupTransport";

    private final IBackupManager backupManager;

    private final IPackageManager packageManager;

    public BackupManagerController() {
        backupManager = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    public BackupSession backup(BackupSessionObserver observer, String... packages) throws RemoteException {

        if (!BACKUP_TRANSPORT.equals(backupManager.getCurrentTransport())) {
            backupManager.selectBackupTransport(BACKUP_TRANSPORT);
        }

        BackupSession backupSession = new BackupSession(backupManager, observer, packages);
        backupSession.start();
        return backupSession;
    }

    public RestoreSession restore(RestoreSessionObserver observer, String... packages) throws RemoteException {

        if (!BACKUP_TRANSPORT.equals(backupManager.getCurrentTransport())) {
            backupManager.selectBackupTransport(BACKUP_TRANSPORT);
        }

        RestoreSession restoreSession = new RestoreSession(backupManager, observer, packages);
        restoreSession.start();
        return restoreSession;
    }

    public List<String> getEligiblePackages() throws RemoteException {
        List<String> results = new ArrayList<>();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0, USER_SYSTEM).getList();

        if (packages != null) {
            for (PackageInfo packageInfo : packages) {

                if (backupManager.isAppEligibleForBackup(packageInfo.packageName)) {
                    results.add(packageInfo.packageName);
                }
            }
        }

        return results;
    }
}
