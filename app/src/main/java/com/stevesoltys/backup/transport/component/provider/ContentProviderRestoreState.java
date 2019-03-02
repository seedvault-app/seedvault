package com.stevesoltys.backup.transport.component.provider;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author Steve Soltys
 */
class ContentProviderRestoreState {

    private ParcelFileDescriptor inputFileDescriptor;

    private PackageInfo[] packages;

    private int packageIndex;

    private int restoreType;

    private ZipInputStream inputStream;

    private byte[] salt;

    private SecretKey secretKey;

    private List<ZipEntry> zipEntries;

    ParcelFileDescriptor getInputFileDescriptor() {
        return inputFileDescriptor;
    }

    void setInputFileDescriptor(ParcelFileDescriptor inputFileDescriptor) {
        this.inputFileDescriptor = inputFileDescriptor;
    }

    ZipInputStream getInputStream() {
        return inputStream;
    }

    void setInputStream(ZipInputStream inputStream) {
        this.inputStream = inputStream;
    }

    int getPackageIndex() {
        return packageIndex;
    }

    void setPackageIndex(int packageIndex) {
        this.packageIndex = packageIndex;
    }

    PackageInfo[] getPackages() {
        return packages;
    }

    void setPackages(PackageInfo[] packages) {
        this.packages = packages;
    }

    int getRestoreType() {
        return restoreType;
    }

    void setRestoreType(int restoreType) {
        this.restoreType = restoreType;
    }

    byte[] getSalt() {
        return salt;
    }

    void setSalt(byte[] salt) {
        this.salt = salt;
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    public List<ZipEntry> getZipEntries() {
        return zipEntries;
    }

    public void setZipEntries(List<ZipEntry> zipEntries) {
        this.zipEntries = zipEntries;
    }
}
