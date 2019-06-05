package com.stevesoltys.backup.transport.component.provider;

import android.app.backup.BackupDataInput;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import com.stevesoltys.backup.security.CipherUtil;
import com.stevesoltys.backup.security.KeyGenerator;
import com.stevesoltys.backup.transport.component.BackupComponent;
import libcore.io.IoUtils;
import org.apache.commons.io.IOUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static android.app.backup.BackupTransport.*;
import static java.util.Objects.requireNonNull;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupComponent implements BackupComponent {

    private static final String TAG = ContentProviderBackupComponent.class.getName();

    private static final String DESTINATION_DESCRIPTION = "Backing up to zip file";

    private static final String TRANSPORT_DATA_MANAGEMENT_LABEL = "";

    private static final int INITIAL_BUFFER_SIZE = 512;

    private final ContentProviderBackupConfiguration configuration;

    private ContentProviderBackupState backupState;

    public ContentProviderBackupComponent(ContentProviderBackupConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void cancelFullBackup() {
        clearBackupState(false);
    }

    @Override
    public int checkFullBackupSize(long size) {
        int result = TRANSPORT_OK;

        if (size <= 0) {
            result = TRANSPORT_PACKAGE_REJECTED;

        } else if (size > configuration.getBackupSizeQuota()) {
            result = TRANSPORT_QUOTA_EXCEEDED;
        }

        return result;
    }

    @Override
    public int clearBackupData(PackageInfo packageInfo) {
        return TRANSPORT_OK;
    }

    @Override
    public String currentDestinationString() {
        return DESTINATION_DESCRIPTION;
    }

    @Override
    public String dataManagementLabel() {
        return TRANSPORT_DATA_MANAGEMENT_LABEL;
    }

    @Override
    public int finishBackup() {
        return clearBackupState(false);
    }

    @Override
    public long getBackupQuota(String packageName, boolean fullBackup) {
        return configuration.getBackupSizeQuota();
    }

    @Override
    public int initializeDevice() {
        return TRANSPORT_OK;
    }

    @Override
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor fileDescriptor) {

        if (backupState != null && backupState.getInputFileDescriptor() != null) {
            Log.e(TAG, "Attempt to initiate full backup while one is in progress");
            return TRANSPORT_ERROR;
        }

        try {
            initializeBackupState();
            backupState.setPackageIndex(backupState.getPackageIndex() + 1);
            backupState.setPackageName(targetPackage.packageName);

            backupState.setInputFileDescriptor(fileDescriptor);
            backupState.setInputStream(new FileInputStream(fileDescriptor.getFileDescriptor()));
            backupState.setBytesTransferred(0);

            Cipher cipher = CipherUtil.startEncrypt(backupState.getSecretKey(), backupState.getSalt());
            backupState.setCipher(cipher);

            ZipEntry zipEntry = new ZipEntry(configuration.getFullBackupDirectory() + backupState.getPackageName());
            backupState.getOutputStream().putNextEntry(zipEntry);

        } catch (Exception ex) {
            Log.e(TAG, "Error creating backup file for " + targetPackage.packageName + ": ", ex);
            clearBackupState(true);
            return TRANSPORT_ERROR;
        }

        return TRANSPORT_OK;
    }

    @Override
    public int performIncrementalBackup(PackageInfo packageInfo, ParcelFileDescriptor data) {
        BackupDataInput backupDataInput = new BackupDataInput(data.getFileDescriptor());

        try {
            initializeBackupState();
            backupState.setPackageIndex(backupState.getPackageIndex() + 1);
            backupState.setPackageName(packageInfo.packageName);

            return transferIncrementalBackupData(backupDataInput);

        } catch (Exception ex) {
            Log.e(TAG, "Error reading backup input: ", ex);
            return TRANSPORT_ERROR;
        }
    }

    @Override
    public long requestBackupTime() {
        return 0;
    }

    @Override
    public long requestFullBackupTime() {
        return 0;
    }

    @Override
    public int sendBackupData(int numBytes) {

        if (backupState == null) {
            Log.e(TAG, "Attempted sendBackupData() before performFullBackup()");
            return TRANSPORT_ERROR;
        }

        long bytesTransferred = backupState.getBytesTransferred() + numBytes;

        if (bytesTransferred > configuration.getBackupSizeQuota()) {
            return TRANSPORT_QUOTA_EXCEEDED;
        }

        InputStream inputStream = backupState.getInputStream();
        ZipOutputStream outputStream = backupState.getOutputStream();

        try {
            byte[] payload = IOUtils.readFully(inputStream, numBytes);

            if (backupState.getCipher() != null) {
                payload = backupState.getCipher().update(payload);
            }

            outputStream.write(payload, 0, numBytes);
            backupState.setBytesTransferred(bytesTransferred);

        } catch (Exception ex) {
            Log.e(TAG, "Error handling backup data for " + backupState.getPackageName() + ": ", ex);
            return TRANSPORT_ERROR;
        }
        return TRANSPORT_OK;
    }

    private int transferIncrementalBackupData(BackupDataInput backupDataInput) throws IOException {
        ZipOutputStream outputStream = backupState.getOutputStream();

        int bufferSize = INITIAL_BUFFER_SIZE;
        byte[] buffer = new byte[bufferSize];

        while (backupDataInput.readNextHeader()) {
            String chunkFileName = Base64.encodeToString(backupDataInput.getKey().getBytes(), Base64.DEFAULT);
            int dataSize = backupDataInput.getDataSize();

            if (dataSize >= 0) {
                ZipEntry zipEntry = new ZipEntry(configuration.getIncrementalBackupDirectory() +
                        backupState.getPackageName() + "/" + chunkFileName);
                outputStream.putNextEntry(zipEntry);

                if (dataSize > bufferSize) {
                    bufferSize = dataSize;
                    buffer = new byte[bufferSize];
                }

                backupDataInput.readEntityData(buffer, 0, dataSize);

                try {
                    if (backupState.getSecretKey() != null) {
                        byte[] payload = Arrays.copyOfRange(buffer, 0, dataSize);
                        SecretKey secretKey = backupState.getSecretKey();
                        byte[] salt = backupState.getSalt();

                        outputStream.write(CipherUtil.encrypt(payload, secretKey, salt));

                    } else {
                        outputStream.write(buffer, 0, dataSize);
                    }

                } catch (Exception ex) {
                    Log.e(TAG, "Error performing incremental backup for " + backupState.getPackageName() + ": ", ex);
                    clearBackupState(true);
                    return TRANSPORT_ERROR;
                }
            }
        }

        return TRANSPORT_OK;
    }

    private void initializeBackupState() throws Exception {
        if (backupState == null) {
            backupState = new ContentProviderBackupState();
        }

        if (backupState.getOutputStream() == null) {
            initializeOutputStream();

            ZipEntry saltZipEntry = new ZipEntry(ContentProviderBackupConstants.SALT_FILE_PATH);
            backupState.getOutputStream().putNextEntry(saltZipEntry);
            backupState.getOutputStream().write(backupState.getSalt());
            backupState.getOutputStream().closeEntry();

            String password = requireNonNull(configuration.getPassword());
            backupState.setSecretKey(KeyGenerator.generate(password, backupState.getSalt()));
        }
    }

    private void initializeOutputStream() throws IOException {
        ContentResolver contentResolver = configuration.getContext().getContentResolver();
        ParcelFileDescriptor outputFileDescriptor = contentResolver.openFileDescriptor(configuration.getUri(), "w");
        backupState.setOutputFileDescriptor(outputFileDescriptor);

        FileOutputStream fileOutputStream = new FileOutputStream(outputFileDescriptor.getFileDescriptor());
        ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream);
        backupState.setOutputStream(zipOutputStream);
    }

    private int clearBackupState(boolean closeFile) {

        if (backupState == null) {
            return TRANSPORT_OK;
        }

        try {
            IoUtils.closeQuietly(backupState.getInputFileDescriptor());
            backupState.setInputFileDescriptor(null);

            ZipOutputStream outputStream = backupState.getOutputStream();

            if (outputStream != null) {

                if (backupState.getCipher() != null) {
                    outputStream.write(backupState.getCipher().doFinal());
                    backupState.setCipher(null);
                }

                outputStream.closeEntry();
            }

            if (backupState.getPackageIndex() == configuration.getPackageCount() || closeFile) {
                if (outputStream != null) {
                    outputStream.finish();
                    outputStream.close();
                }

                IoUtils.closeQuietly(backupState.getOutputFileDescriptor());
                backupState = null;
            }

        } catch (Exception ex) {
            Log.e(TAG, "Error cancelling full backup: ", ex);
            return TRANSPORT_ERROR;
        }

        return TRANSPORT_OK;
    }
}
