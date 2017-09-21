package com.stevesoltys.backup.transport.component.provider.restore;

import android.app.backup.BackupDataOutput;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

import static android.app.backup.BackupTransport.NO_MORE_DATA;
import static android.app.backup.BackupTransport.TRANSPORT_ERROR;
import static android.app.backup.BackupTransport.TRANSPORT_OK;
import static android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED;
import static android.app.backup.RestoreDescription.TYPE_FULL_STREAM;
import static android.app.backup.RestoreDescription.TYPE_KEY_VALUE;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration.FULL_BACKUP_DIRECTORY;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration.INCREMENTAL_BACKUP_DIRECTORY;

/**
 * TODO: Clean this up. Much of it was taken from the LocalTransport implementation.
 *
 * @author Steve Soltys
 */
public class ContentProviderRestoreComponent implements RestoreComponent {

    private static final String TAG = ContentProviderRestoreComponent.class.getName();

    private static final int DEFAULT_RESTORE_SET = 1;

    private static final int DEFAULT_BUFFER_SIZE = 2048;

    private ContentProviderBackupConfiguration configuration;

    private ContentProviderRestoreState restoreState;

    public ContentProviderRestoreComponent(ContentProviderBackupConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        Log.i(TAG, "startRestore() " + Arrays.asList(packages));

        restoreState = new ContentProviderRestoreState();
        restoreState.setPackages(packages);
        restoreState.setPackageIndex(-1);

        return TRANSPORT_OK;
    }

    private ParcelFileDescriptor buildFileDescriptor() throws FileNotFoundException {
        ContentResolver contentResolver = configuration.getContext().getContentResolver();

        return contentResolver.openFileDescriptor(configuration.getUri(), "r");
    }

    private ZipInputStream buildInputStream(ParcelFileDescriptor inputFileDescriptor) throws FileNotFoundException {
        FileInputStream fileInputStream = new FileInputStream(inputFileDescriptor.getFileDescriptor());
        return new ZipInputStream(fileInputStream);
    }

    private boolean containsPackageFile(String fileName) throws IOException, InvalidKeyException,
            InvalidAlgorithmParameterException {

        ParcelFileDescriptor inputFileDescriptor = buildFileDescriptor();
        ZipInputStream inputStream = buildInputStream(inputFileDescriptor);

        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {

            if (zipEntry.getName().startsWith(fileName)) {
                IoUtils.closeQuietly(inputStream);
                IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                return true;
            }

            inputStream.closeEntry();
        }

        IoUtils.closeQuietly(inputStream);
        IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
        return false;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        Log.i(TAG, "nextRestorePackage()");

        if (restoreState == null) {
            throw new IllegalStateException("startRestore not called");
        }

        int packageIndex = restoreState.getPackageIndex();
        PackageInfo[] packages = restoreState.getPackages();

        while (++packageIndex < packages.length) {
            restoreState.setPackageIndex(packageIndex);
            String name = packages[packageIndex].packageName;

            try {
                if (containsPackageFile(INCREMENTAL_BACKUP_DIRECTORY + name)) {
                    Log.i(TAG, "  nextRestorePackage(TYPE_KEY_VALUE) @ " + packageIndex + " = " + name);

                    restoreState.setRestoreType(TYPE_KEY_VALUE);
                    return new RestoreDescription(name, restoreState.getRestoreType());

                } else if (containsPackageFile(FULL_BACKUP_DIRECTORY + name)) {
                    Log.i(TAG, "  nextRestorePackage(TYPE_FULL_STREAM) @ " + packageIndex + " = " + name);

                    restoreState.setRestoreType(TYPE_FULL_STREAM);
                    return new RestoreDescription(name, restoreState.getRestoreType());
                }

            } catch (IOException | InvalidKeyException | InvalidAlgorithmParameterException e) {
                Log.e(TAG, "  ... package @ " + packageIndex + " = " + name + " error:", e);
            }

            Log.i(TAG, "  ... package @ " + packageIndex + " = " + name + " has no data; skipping");
        }

        Log.i(TAG, "  no more packages to restore");
        return RestoreDescription.NO_MORE_PACKAGES;
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        Log.i(TAG, "getRestoreData() " + outputFileDescriptor.toString());

        if (restoreState == null) {
            throw new IllegalStateException("startRestore not called");

        } else if (restoreState.getPackageIndex() < 0) {
            throw new IllegalStateException("nextRestorePackage not called");

        } else if (restoreState.getRestoreType() != TYPE_KEY_VALUE) {
            throw new IllegalStateException("getRestoreData(fd) for non-key/value dataset");
        }

        PackageInfo packageInfo = restoreState.getPackages()[restoreState.getPackageIndex()];
        BackupDataOutput backupDataOutput = new BackupDataOutput(outputFileDescriptor.getFileDescriptor());

        try {
            return transferIncrementalRestoreData(packageInfo.packageName, backupDataOutput);

        } catch (Exception ex) {
            Log.e(TAG, "Unable to read backup records: ", ex);
            return TRANSPORT_ERROR;
        }
    }

    private int transferIncrementalRestoreData(String packageName, BackupDataOutput backupDataOutput)
            throws IOException, InvalidAlgorithmParameterException, InvalidKeyException {
        Log.i(TAG, "transferIncrementalRestoreData() " + packageName);

        ParcelFileDescriptor inputFileDescriptor = buildFileDescriptor();
        ZipInputStream inputStream = buildInputStream(inputFileDescriptor);

        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {

            if (zipEntry.getName().startsWith(INCREMENTAL_BACKUP_DIRECTORY + packageName)) {
                String fileName = new File(zipEntry.getName()).getName();
                String blobKey = new String(Base64.decode(fileName, Base64.DEFAULT));

                byte[] backupData = Streams.readFullyNoClose(inputStream);
                Log.i(TAG, "Backup data: " + packageName + ": " + backupData.length);

                backupDataOutput.writeEntityHeader(blobKey, backupData.length);
                backupDataOutput.writeEntityData(backupData, backupData.length);
            }

            inputStream.closeEntry();
        }

        IoUtils.closeQuietly(inputStream);
        IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
        return TRANSPORT_OK;
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor fileDescriptor) {

        if (restoreState.getRestoreType() != TYPE_FULL_STREAM) {
            throw new IllegalStateException("Asked for full restore data for non-stream package");
        }

        ParcelFileDescriptor inputFileDescriptor = restoreState.getInputFileDescriptor();
        ZipInputStream inputStream = restoreState.getInputStream();

        if (inputFileDescriptor == null) {
            String name = restoreState.getPackages()[restoreState.getPackageIndex()].packageName;

            try {
                inputFileDescriptor = buildFileDescriptor();
                restoreState.setInputFileDescriptor(inputFileDescriptor);

                inputStream = buildInputStream(inputFileDescriptor);
                restoreState.setInputStream(inputStream);

            } catch (FileNotFoundException ex) {
                Log.e(TAG, "Unable to read archive for " + name, ex);

                if(inputFileDescriptor != null) {
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                }

                return TRANSPORT_ERROR;
            }

            try {

                ZipEntry zipEntry;
                while ((zipEntry = inputStream.getNextEntry()) != null) {

                    if (zipEntry.getName().equals(FULL_BACKUP_DIRECTORY + name)) {
                        break;
                    }

                    inputStream.closeEntry();
                }

                if (zipEntry == null) {
                    IoUtils.closeQuietly(inputStream);
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                    return TRANSPORT_PACKAGE_REJECTED;
                }

            } catch (IOException ex) {
                Log.e(TAG, "Unable to read archive for " + name, ex);

                IoUtils.closeQuietly(inputStream);
                IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                return TRANSPORT_PACKAGE_REJECTED;
            }
        }

        if (restoreState.getOutputStream() == null) {
            restoreState.setOutputStream(new FileOutputStream(fileDescriptor.getFileDescriptor()));
        }

        OutputStream outputStream = restoreState.getOutputStream();

        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int bytesRead = NO_MORE_DATA;

        try {
            bytesRead = inputStream.read(buffer);

            if (bytesRead < 0) {
                bytesRead = NO_MORE_DATA;

            } else if (bytesRead == 0) {
                Log.w(TAG, "read() of archive file returned 0; treating as EOF");
                bytesRead = NO_MORE_DATA;

            } else {
                outputStream.write(buffer, 0, bytesRead);
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception while streaming restore data: ", e);
            return TRANSPORT_ERROR;

        } finally {
            try {
                if (bytesRead == NO_MORE_DATA) {
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                    IoUtils.closeQuietly(inputStream);
                    IoUtils.closeQuietly(outputStream);

                    fileDescriptor.close();

                    restoreState.setInputFileDescriptor(null);
                    restoreState.setInputStream(null);
                    restoreState.setOutputStream(null);
                }
            } catch (IOException ex) {
                Log.e(TAG, "Exception while closing socket for restore: ", ex);
            }
        }

        return bytesRead;
    }

    @Override
    public int abortFullRestore() {
        resetFullRestoreState();
        return TRANSPORT_OK;
    }

    @Override
    public long getCurrentRestoreSet() {
        return DEFAULT_RESTORE_SET;
    }

    @Override
    public void finishRestore() {
        if (restoreState.getRestoreType() == TYPE_FULL_STREAM) {
            resetFullRestoreState();
        }

        restoreState = null;
    }

    @Override
    public RestoreSet[] getAvailableRestoreSets() {
        return new RestoreSet[]{new RestoreSet("Local disk image", "flash", DEFAULT_RESTORE_SET)};
    }

    private void resetFullRestoreState() {

        if(restoreState == null) {
            return;
        }

        if (restoreState.getRestoreType() != TYPE_FULL_STREAM) {
            throw new IllegalStateException("abortFullRestore() but not currently restoring");
        }

        IoUtils.closeQuietly(restoreState.getInputFileDescriptor());
        IoUtils.closeQuietly(restoreState.getInputStream());
        IoUtils.closeQuietly(restoreState.getOutputStream());

        restoreState = null;
    }
}
