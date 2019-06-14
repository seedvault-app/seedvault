package com.stevesoltys.backup.service;

import android.app.backup.IBackupManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.List;
import java.util.Set;

import static com.google.android.collect.Sets.newArraySet;

/**
 * @author Steve Soltys
 */
public class PackageService {

    private final IBackupManager backupManager;

    private final IPackageManager packageManager;

    private static final Set<String> IGNORED_PACKAGES = newArraySet(
            "com.android.externalstorage",
            "com.android.providers.downloads.ui",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.calendar",
            "com.android.providers.contacts",
            "com.stevesoltys.backup"
    );

    public PackageService() {
        backupManager = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    public String[] getEligiblePackages() throws RemoteException {
        List<PackageInfo> packages = packageManager.getInstalledPackages(0, UserHandle.USER_SYSTEM).getList();
        String[] packageArray = packages.stream()
                .map(packageInfo -> packageInfo.packageName)
                .filter(packageName -> !IGNORED_PACKAGES.contains(packageName))
                .toArray(String[]::new);

        return backupManager.filterAppsEligibleForBackup(packageArray);
    }
}
