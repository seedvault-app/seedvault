package com.stevesoltys.backup.transport.component.provider;

import android.app.backup.BackupDataOutput;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import com.android.internal.util.Preconditions;
import com.stevesoltys.backup.security.CipherUtil;
import com.stevesoltys.backup.security.KeyGenerator;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import libcore.io.IoUtils;
import libcore.io.Streams;

import javax.crypto.SecretKey;
import java.io.*;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static android.app.backup.BackupTransport.*;
import static android.app.backup.RestoreDescription.TYPE_FULL_STREAM;
import static android.app.backup.RestoreDescription.TYPE_KEY_VALUE;

/**
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
        restoreState = new ContentProviderRestoreState();
        restoreState.setPackages(packages);
        restoreState.setPackageIndex(-1);

        if (configuration.getPassword() != null && !configuration.getPassword().isEmpty()) {
            try {
                ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
                ZipInputStream inputStream = buildInputStream(inputFileDescriptor);
                seekToEntry(inputStream, ContentProviderBackupConstants.SALT_FILE_PATH);

                restoreState.setSalt(Streams.readFullyNoClose(inputStream));
                restoreState.setSecretKey(KeyGenerator.generate(configuration.getPassword(), restoreState.getSalt()));

                IoUtils.closeQuietly(inputFileDescriptor);
                IoUtils.closeQuietly(inputStream);

            } catch (Exception ex) {
                Log.e(TAG, "Salt not found", ex);
            }
        }

        try {
            List<ZipEntry> zipEntries = new LinkedList<>();

            ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
            ZipInputStream inputStream = buildInputStream(inputFileDescriptor);

            ZipEntry zipEntry;
            while ((zipEntry = inputStream.getNextEntry()) != null) {
                zipEntries.add(zipEntry);
                inputStream.closeEntry();
            }

            IoUtils.closeQuietly(inputFileDescriptor);
            IoUtils.closeQuietly(inputStream);

            restoreState.setZipEntries(zipEntries);

        } catch (Exception ex) {
            Log.e(TAG, "Salt not found", ex);
        }

        return TRANSPORT_OK;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        Preconditions.checkNotNull(restoreState, "startRestore() not called");

        int packageIndex = restoreState.getPackageIndex();
        PackageInfo[] packages = restoreState.getPackages();

        while (++packageIndex < packages.length) {
            restoreState.setPackageIndex(packageIndex);
            String name = packages[packageIndex].packageName;

            if (containsPackageFile(configuration.getIncrementalBackupDirectory() + name)) {
                restoreState.setRestoreType(TYPE_KEY_VALUE);
                return new RestoreDescription(name, restoreState.getRestoreType());

            } else if (containsPackageFile(configuration.getFullBackupDirectory() + name)) {
                restoreState.setRestoreType(TYPE_FULL_STREAM);
                return new RestoreDescription(name, restoreState.getRestoreType());
            }
        }
        return RestoreDescription.NO_MORE_PACKAGES;
    }

    private boolean containsPackageFile(String fileName) {
        return restoreState.getZipEntries().stream()
                .anyMatch(zipEntry -> zipEntry.getName().startsWith(fileName));
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        Preconditions.checkState(restoreState != null, "startRestore() not called");
        Preconditions.checkState(restoreState.getPackageIndex() >= 0, "nextRestorePackage() not called");
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_KEY_VALUE,
                "getRestoreData() for non-key/value dataset");

        PackageInfo packageInfo = restoreState.getPackages()[restoreState.getPackageIndex()];

        try {
            return transferIncrementalRestoreData(packageInfo.packageName, outputFileDescriptor);

        } catch (Exception ex) {
            Log.e(TAG, "Unable to read backup records: ", ex);
            return TRANSPORT_ERROR;
        }
    }

    private int transferIncrementalRestoreData(String packageName, ParcelFileDescriptor outputFileDescriptor)
            throws Exception {

        ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
        ZipInputStream inputStream = buildInputStream(inputFileDescriptor);
        BackupDataOutput backupDataOutput = new BackupDataOutput(outputFileDescriptor.getFileDescriptor());

        Optional<ZipEntry> zipEntryOptional = seekToEntry(inputStream,
                configuration.getIncrementalBackupDirectory() + packageName);

        while (zipEntryOptional.isPresent()) {
            String fileName = new File(zipEntryOptional.get().getName()).getName();
            String blobKey = new String(Base64.decode(fileName, Base64.DEFAULT));

            byte[] backupData = readBackupData(inputStream);
            backupDataOutput.writeEntityHeader(blobKey, backupData.length);
            backupDataOutput.writeEntityData(backupData, backupData.length);
            inputStream.closeEntry();

            zipEntryOptional = seekToEntry(inputStream, configuration.getIncrementalBackupDirectory() + packageName);
        }

        IoUtils.closeQuietly(inputFileDescriptor);
        IoUtils.closeQuietly(outputFileDescriptor);
        return TRANSPORT_OK;
    }

    private ParcelFileDescriptor buildInputFileDescriptor() throws FileNotFoundException {
        ContentResolver contentResolver = configuration.getContext().getContentResolver();
        return contentResolver.openFileDescriptor(configuration.getUri(), "r");
    }

    private ZipInputStream buildInputStream(ParcelFileDescriptor inputFileDescriptor) throws FileNotFoundException {
        FileInputStream fileInputStream = new FileInputStream(inputFileDescriptor.getFileDescriptor());
        return new ZipInputStream(fileInputStream);
    }

    private Optional<ZipEntry> seekToEntry(ZipInputStream inputStream, String entryPath) throws IOException {
        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {

            if (zipEntry.getName().startsWith(entryPath)) {
                return Optional.of(zipEntry);
            }
            inputStream.closeEntry();
        }

        return Optional.empty();
    }

    private byte[] readBackupData(ZipInputStream inputStream) throws Exception {
        byte[] backupData = Streams.readFullyNoClose(inputStream);
        SecretKey secretKey = restoreState.getSecretKey();
        byte[] initializationVector = restoreState.getSalt();

        if (secretKey != null) {
            backupData = CipherUtil.decrypt(backupData, secretKey, initializationVector);
        }

        return backupData;
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor outputFileDescriptor) {
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_FULL_STREAM,
                "Asked for full restore data for non-stream package");

        ParcelFileDescriptor inputFileDescriptor = restoreState.getInputFileDescriptor();

        if (inputFileDescriptor == null) {
            String name = restoreState.getPackages()[restoreState.getPackageIndex()].packageName;

            try {
                inputFileDescriptor = buildInputFileDescriptor();
                restoreState.setInputFileDescriptor(inputFileDescriptor);

                ZipInputStream inputStream = buildInputStream(inputFileDescriptor);
                restoreState.setInputStream(inputStream);

                if (!seekToEntry(inputStream, configuration.getFullBackupDirectory() + name).isPresent()) {
                    IoUtils.closeQuietly(inputFileDescriptor);
                    IoUtils.closeQuietly(outputFileDescriptor);
                    return TRANSPORT_PACKAGE_REJECTED;
                }

            } catch (IOException ex) {
                Log.e(TAG, "Unable to read archive for " + name, ex);

                IoUtils.closeQuietly(inputFileDescriptor);
                IoUtils.closeQuietly(outputFileDescriptor);
                return TRANSPORT_PACKAGE_REJECTED;
            }
        }

        return transferFullRestoreData(outputFileDescriptor);
    }

    private int transferFullRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        ZipInputStream inputStream = restoreState.getInputStream();
        OutputStream outputStream = new FileOutputStream(outputFileDescriptor.getFileDescriptor());

        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int bytesRead = NO_MORE_DATA;

        try {
            bytesRead = inputStream.read(buffer);

            if (bytesRead <= 0) {
                bytesRead = NO_MORE_DATA;
            } else {
                outputStream.write(buffer, 0, bytesRead);
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception while streaming restore data: ", e);
            return TRANSPORT_ERROR;

        } finally {
            if (bytesRead == NO_MORE_DATA) {

                if (restoreState.getInputFileDescriptor() != null) {
                    IoUtils.closeQuietly(restoreState.getInputFileDescriptor());
                }

                restoreState.setInputFileDescriptor(null);
                restoreState.setInputStream(null);
            }

            IoUtils.closeQuietly(outputFileDescriptor);
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
        Preconditions.checkNotNull(restoreState);
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_FULL_STREAM);

        IoUtils.closeQuietly(restoreState.getInputFileDescriptor());
        restoreState = null;
    }
}
