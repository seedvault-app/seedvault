package com.stevesoltys.backup.transport.component.provider;

import android.annotation.Nullable;
import android.app.backup.BackupDataOutput;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.stevesoltys.backup.security.CipherUtil;
import com.stevesoltys.backup.security.KeyGenerator;
import com.stevesoltys.backup.transport.component.RestoreComponent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.SecretKey;

import libcore.io.IoUtils;
import libcore.io.Streams;

import static android.app.backup.BackupTransport.NO_MORE_DATA;
import static android.app.backup.BackupTransport.TRANSPORT_ERROR;
import static android.app.backup.BackupTransport.TRANSPORT_OK;
import static android.app.backup.BackupTransport.TRANSPORT_PACKAGE_REJECTED;
import static android.app.backup.RestoreDescription.TYPE_FULL_STREAM;
import static android.app.backup.RestoreDescription.TYPE_KEY_VALUE;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConstants.DEFAULT_FULL_BACKUP_DIRECTORY;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConstants.DEFAULT_INCREMENTAL_BACKUP_DIRECTORY;
import static java.util.Objects.requireNonNull;

/**
 * @author Steve Soltys
 */
public class ContentProviderRestoreComponent implements RestoreComponent {

    private static final String TAG = ContentProviderRestoreComponent.class.getName();

    private static final int DEFAULT_RESTORE_SET = 1;

    private static final int DEFAULT_BUFFER_SIZE = 2048;

    @Nullable
    private String password;
    @Nullable
    private Uri fileUri;

    private ContentProviderRestoreState restoreState;

    private final Context context;

    public ContentProviderRestoreComponent(Context context) {
        this.context = context;
    }

    @Override
    public void prepareRestore(String password, Uri fileUri) {
        this.password = password;
        this.fileUri = fileUri;
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        restoreState = new ContentProviderRestoreState();
        restoreState.setPackages(packages);
        restoreState.setPackageIndex(-1);

        String password = requireNonNull(this.password);

        if (!password.isEmpty()) {
            try {
                ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
                ZipInputStream inputStream = buildInputStream(inputFileDescriptor);
                seekToEntry(inputStream, ContentProviderBackupConstants.SALT_FILE_PATH);

                restoreState.setSalt(Streams.readFullyNoClose(inputStream));
                restoreState.setSecretKey(KeyGenerator.generate(password, restoreState.getSalt()));

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
            Log.e(TAG, "Error while caching zip entries", ex);
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

            if (containsPackageFile(DEFAULT_INCREMENTAL_BACKUP_DIRECTORY + name)) {
                restoreState.setRestoreType(TYPE_KEY_VALUE);
                return new RestoreDescription(name, restoreState.getRestoreType());

            } else if (containsPackageFile(DEFAULT_FULL_BACKUP_DIRECTORY + name)) {
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
                DEFAULT_INCREMENTAL_BACKUP_DIRECTORY + packageName);

        while (zipEntryOptional.isPresent()) {
            String fileName = new File(zipEntryOptional.get().getName()).getName();
            String blobKey = new String(Base64.decode(fileName, Base64.DEFAULT));

            byte[] backupData = readBackupData(inputStream);
            backupDataOutput.writeEntityHeader(blobKey, backupData.length);
            backupDataOutput.writeEntityData(backupData, backupData.length);
            inputStream.closeEntry();

            zipEntryOptional = seekToEntry(inputStream, DEFAULT_INCREMENTAL_BACKUP_DIRECTORY + packageName);
        }

        IoUtils.closeQuietly(inputFileDescriptor);
        IoUtils.closeQuietly(outputFileDescriptor);
        return TRANSPORT_OK;
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

                if (!seekToEntry(inputStream, DEFAULT_FULL_BACKUP_DIRECTORY + name).isPresent()) {
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

                if (restoreState.getCipher() != null) {
                    buffer = restoreState.getCipher().doFinal();
                    bytesRead = buffer.length;

                    outputStream.write(buffer, 0, bytesRead);
                    restoreState.setCipher(null);
                }

            } else {
                if (restoreState.getSecretKey() != null) {
                    SecretKey secretKey = restoreState.getSecretKey();
                    byte[] salt = restoreState.getSalt();

                    if (restoreState.getCipher() == null) {
                        restoreState.setCipher(CipherUtil.startDecrypt(secretKey, salt));
                    }

                    buffer = restoreState.getCipher().update(Arrays.copyOfRange(buffer, 0, bytesRead));
                    bytesRead = buffer.length;
                }

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

    private ParcelFileDescriptor buildInputFileDescriptor() throws FileNotFoundException {
        ContentResolver contentResolver = context.getContentResolver();
        return contentResolver.openFileDescriptor(requireNonNull(fileUri), "r");
    }

    private ZipInputStream buildInputStream(ParcelFileDescriptor inputFileDescriptor) {
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
}
