package com.stevesoltys.backup.service;

import android.app.backup.IBackupManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Steve Soltys
 */
public class PackageService {

    private final IBackupManager backupManager;

    private final IPackageManager packageManager;

    public PackageService() {
        backupManager = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    public List<String> getEligiblePackages() throws RemoteException {
        List<String> results = new ArrayList<>();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0, UserHandle.USER_SYSTEM).getList();

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
