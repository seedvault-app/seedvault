package com.stevesoltys.backup.transport.component.stub;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;

/**
 * @author Steve Soltys
 */
public class StubRestoreComponent implements RestoreComponent {

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        return 0;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        return null;
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        return 0;
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        return 0;
    }

    @Override
    public int abortFullRestore() {
        return 0;
    }

    @Override
    public long getCurrentRestoreSet() {
        return 0;
    }

    @Override
    public void finishRestore() {

    }

    @Override
    public RestoreSet[] getAvailableRestoreSets() {
        return new RestoreSet[0];
    }
}
