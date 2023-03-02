/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts;

import static android.Manifest.permission.READ_CONTACTS;
import static android.Manifest.permission.WRITE_CONTACTS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

public class ContactsBackupAgent extends BackupAgent implements FullBackupFileHandler {

    private final static String TAG = "ContactsBackupAgent";
    final static String BACKUP_FILE = "backup-v1.tar";
    final static boolean DEBUG = false; // don't commit with true

    private final Context testContext;
    private final FullBackupFileHandler fileHandler;

    public ContactsBackupAgent() {
        super();
        testContext = null;
        fileHandler = this;
    }

    /**
     * Only for testing
     */
    ContactsBackupAgent(Context context, FullBackupFileHandler fileHandler) {
        super();
        testContext = context;
        attachBaseContext(context);
        this.fileHandler = fileHandler;
    }

    private Context getContext() {
        if (testContext != null) return testContext;
        else return getBaseContext();
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        if (shouldAvoidBackup(data)) {
            Log.w(TAG, "onFullBackup - will not back up due to flags: " + data.getTransportFlags());
            return;
        }

        if (getContext().checkSelfPermission(READ_CONTACTS) != PERMISSION_GRANTED) {
            throw new IOException("Permission READ_CONTACTS not granted.");
        }

        // get VCARDs as an InputStream
        VCardExporter vCardExporter = new VCardExporter(getContentResolver());
        Optional<InputStream> optionalInputStream = vCardExporter.getVCardInputStream();
        if (!optionalInputStream.isPresent()) {
            Log.i(TAG, "onFullBackup - found no contacts. Not backing up.");
            return;
        }
        InputStream vCardInputStream = optionalInputStream.orElseThrow(AssertionError::new);

        Log.d(TAG, "onFullBackup - will do backup");

        // create backup file as an OutputStream
        File backupFile = new File(getContext().getFilesDir(), BACKUP_FILE);
        FileOutputStream backupFileOutputStream = new FileOutputStream(backupFile);

        // write VCARDs into backup file
        try {
            copyStreams(vCardInputStream, backupFileOutputStream);
        } finally {
            backupFileOutputStream.close();
            vCardInputStream.close();
        }

        // backup file
        fileHandler.fullBackupFile(backupFile, data);

        // delete file when done
        if (!backupFile.delete()) {
            Log.w(TAG, "Could not delete: " + backupFile.getAbsolutePath());
        }
    }

    private boolean shouldAvoidBackup(FullBackupDataOutput data) {
        boolean isEncrypted = (data.getTransportFlags() & FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED) != 0;
        boolean isDeviceTransfer = (data.getTransportFlags() & FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0;
        return !isEncrypted && !isDeviceTransfer;
    }

    private void copyStreams(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buf = new byte[8192];
        int length;
        while ((length = inputStream.read(buf)) > 0) {
            outputStream.write(buf, 0, length);
            if (DEBUG) {
                Log.e(TAG, new String(buf, 0, length));
            }
        }
    }

    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size, File destination, int type, long mode, long mtime) throws IOException {
        Log.d(TAG, "onRestoreFile " + mode);
        super.onRestoreFile(data, size, destination, type, mode, mtime);

        if (getContext().checkSelfPermission(WRITE_CONTACTS) != PERMISSION_GRANTED) {
            throw new IOException("Permission WRITE_CONTACTS not granted.");
        }

        File backupFile = new File(getContext().getFilesDir(), BACKUP_FILE);

        try (FileInputStream backupFileInputStream = new FileInputStream(backupFile)) {
            VCardImporter vCardImporter = new VCardImporter(getContentResolver());
            vCardImporter.importFromStream(backupFileInputStream);
        }

        // delete file when done
        if (!backupFile.delete()) {
            Log.w(TAG, "Could not delete: " + backupFile.getAbsolutePath());
        }
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        super.onQuotaExceeded(backupDataBytes, quotaBytes);
        // TODO show error notification?
        Log.e(TAG, "onQuotaExceeded " + backupDataBytes + " / " + quotaBytes);
    }

    /**
     * The methods below are for key/value backup/restore and should never get called
     **/

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState) {
        Log.e(TAG, "onBackup noSuper");
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
        Log.e(TAG, "onRestore noSuper");
    }

}
