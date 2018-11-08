package com.stevesoltys.backup.transport;

import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import com.android.internal.util.Preconditions;
import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransport extends BackupTransport {

    private static final String TRANSPORT_DIRECTORY_NAME =
            "com.stevesoltys.backup.transport.ConfigurableBackupTransport";

    private BackupComponent backupComponent;

    private RestoreComponent restoreComponent;

    public ConfigurableBackupTransport() {
        backupComponent = null;
        restoreComponent = null;
    }

    public void initialize(BackupComponent backupComponent, RestoreComponent restoreComponent) {
        Preconditions.checkNotNull(backupComponent);
        Preconditions.checkNotNull(restoreComponent);
        Preconditions.checkState(!isActive());

        this.restoreComponent = restoreComponent;
        this.backupComponent = backupComponent;
    }

    public void reset() {
        backupComponent = null;
        restoreComponent = null;
    }

    public boolean isActive() {
        return backupComponent != null && restoreComponent != null;
    }

    public BackupComponent getBackupComponent() {
        return backupComponent;
    }

    public RestoreComponent getRestoreComponent() {
        return restoreComponent;
    }

    @Override
    public String transportDirName() {
        return TRANSPORT_DIRECTORY_NAME;
    }

    @Override
    public String name() {
        // TODO: Make this class non-static in ConfigurableBackupTransportService and use Context and a ComponentName.
        return this.getClass().getName();
    }

    @Override
    public long requestBackupTime() {
        return backupComponent.requestBackupTime();
    }

    @Override
    public String dataManagementLabel() {
        return backupComponent.dataManagementLabel();
    }

    @Override
    public int initializeDevice() {
        return backupComponent.initializeDevice();
    }

    @Override
    public String currentDestinationString() {
        return backupComponent.currentDestinationString();
    }

    @Override
    public int performBackup(PackageInfo targetPackage, ParcelFileDescriptor fileDescriptor) {
        return backupComponent.performIncrementalBackup(targetPackage, fileDescriptor);
    }

    @Override
    public int checkFullBackupSize(long size) {
        return backupComponent.checkFullBackupSize(size);
    }

    @Override
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor fileDescriptor) {
        return backupComponent.performFullBackup(targetPackage, fileDescriptor);
    }

    @Override
    public int sendBackupData(int numBytes) {
        return backupComponent.sendBackupData(numBytes);
    }

    @Override
    public void cancelFullBackup() {
        backupComponent.cancelFullBackup();
    }

    @Override
    public int finishBackup() {
        return backupComponent.finishBackup();
    }

    @Override
    public long requestFullBackupTime() {
        return backupComponent.requestFullBackupTime();
    }

    @Override
    public long getBackupQuota(String packageName, boolean isFullBackup) {
        return backupComponent.getBackupQuota(packageName, isFullBackup);
    }

    @Override
    public int clearBackupData(PackageInfo packageInfo) {
        return backupComponent.clearBackupData(packageInfo);
    }

    @Override
    public long getCurrentRestoreSet() {
        return restoreComponent.getCurrentRestoreSet();
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        return restoreComponent.startRestore(token, packages);
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        return restoreComponent.getNextFullRestoreDataChunk(socket);
    }

    @Override
    public RestoreSet[] getAvailableRestoreSets() {
        return restoreComponent.getAvailableRestoreSets();
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        return restoreComponent.nextRestorePackage();
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        return restoreComponent.getRestoreData(outputFileDescriptor);
    }

    @Override
    public int abortFullRestore() {
        return restoreComponent.abortFullRestore();
    }

    @Override
    public void finishRestore() {
        restoreComponent.finishRestore();
    }
}
