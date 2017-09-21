package com.stevesoltys.backup.transport.component.provider.restore;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import java.io.OutputStream;
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

    private OutputStream outputStream;

    PackageInfo[] getPackages() {
        return packages;
    }

    void setPackages(PackageInfo[] packages) {
        this.packages = packages;
    }

    int getPackageIndex() {
        return packageIndex;
    }

    void setPackageIndex(int packageIndex) {
        this.packageIndex = packageIndex;
    }

    int getRestoreType() {
        return restoreType;
    }

    void setRestoreType(int restoreType) {
        this.restoreType = restoreType;
    }

    OutputStream getOutputStream() {
        return outputStream;
    }

    void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    ZipInputStream getInputStream() {
        return inputStream;
    }

    void setInputStream(ZipInputStream inputStream) {
        this.inputStream = inputStream;
    }

    ParcelFileDescriptor getInputFileDescriptor() {
        return inputFileDescriptor;
    }

    void setInputFileDescriptor(ParcelFileDescriptor inputFileDescriptor) {
        this.inputFileDescriptor = inputFileDescriptor;
    }
}
