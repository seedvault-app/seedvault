package com.stevesoltys.backup.transport.component;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

/**
 * @author Steve Soltys
 */
public interface BackupComponent {

    String currentDestinationString();

    String dataManagementLabel();

    int initializeDevice();

    int clearBackupData(PackageInfo packageInfo);

    int finishBackup();

    int performIncrementalBackup(PackageInfo targetPackage, ParcelFileDescriptor data, int flags);

    int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor fileDescriptor);

    int checkFullBackupSize(long size);

    int sendBackupData(int numBytes);

    void cancelFullBackup();

    long getBackupQuota(String packageName, boolean fullBackup);

    long requestBackupTime();

    long requestFullBackupTime();

    void backupFinished();
}
