package com.stevesoltys.backup.transport;

import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderRestoreComponent;

/**
 * @author Steve Soltys
 */
public class ConfigurableBackupTransport extends BackupTransport {

    private static final String TRANSPORT_DIRECTORY_NAME =
            "com.stevesoltys.backup.transport.ConfigurableBackupTransport";

    public static final String DEFAULT_FULL_BACKUP_DIRECTORY = "full/";

    public static final String DEFAULT_INCREMENTAL_BACKUP_DIRECTORY = "incr/";

    public static final long DEFAULT_BACKUP_QUOTA = Long.MAX_VALUE;

    private final BackupComponent backupComponent;

    private final RestoreComponent restoreComponent;

    ConfigurableBackupTransport(Context context) {
        backupComponent = new ContentProviderBackupComponent(context);
        restoreComponent = new ContentProviderRestoreComponent(context);
    }

    public void prepareBackup(int numberOfPackages) {
        backupComponent.prepareBackup(numberOfPackages);
    }

    public void prepareRestore(String password, Uri fileUri) {
        restoreComponent.prepareRestore(password, fileUri);
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
