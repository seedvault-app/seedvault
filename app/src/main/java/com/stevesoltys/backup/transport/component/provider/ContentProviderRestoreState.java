package com.stevesoltys.backup.transport.component.provider;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

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
}
