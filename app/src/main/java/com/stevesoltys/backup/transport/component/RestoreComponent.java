package com.stevesoltys.backup.transport.component;

import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

/**
 * @author Steve Soltys
 */
public interface RestoreComponent {

    int startRestore(long token, PackageInfo[] packages);

    RestoreDescription nextRestorePackage();

    int getRestoreData(ParcelFileDescriptor outputFileDescriptor);

    int getNextFullRestoreDataChunk(ParcelFileDescriptor socket);

    int abortFullRestore();

    long getCurrentRestoreSet();

    void finishRestore();

    RestoreSet[] getAvailableRestoreSets();
}
