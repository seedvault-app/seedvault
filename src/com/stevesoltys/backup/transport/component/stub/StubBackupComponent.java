package com.stevesoltys.backup.transport.component.stub;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import com.stevesoltys.backup.transport.component.BackupComponent;

/**
 * @author Steve Soltys
 */
public class StubBackupComponent implements BackupComponent {

    @Override
    public String currentDestinationString() {
        return null;
    }

    @Override
    public String dataManagementLabel() {
        return null;
    }

    @Override
    public int initializeDevice() {
        return 0;
    }

    @Override
    public int clearBackupData(PackageInfo packageInfo) {
        return 0;
    }

    @Override
    public int finishBackup() {
        return 0;
    }

    @Override
    public int performIncrementalBackup(PackageInfo targetPackage, ParcelFileDescriptor data) {
        return 0;
    }

    @Override
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor fileDescriptor) {
        return 0;
    }

    @Override
    public int checkFullBackupSize(long size) {
        return 0;
    }

    @Override
    public int sendBackupData(int numBytes) {
        return 0;
    }

    @Override
    public void cancelFullBackup() {

    }

    @Override
    public long getBackupQuota(String packageName, boolean fullBackup) {
        return 0;
    }

    @Override
    public long requestBackupTime() {
        return 0;
    }

    @Override
    public long requestFullBackupTime() {
        return 0;
    }
}
