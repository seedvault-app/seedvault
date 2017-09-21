package com.stevesoltys.backup.transport.component.provider.backup;

import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.util.zip.ZipOutputStream;

/**
 * @author Steve Soltys
 */
class ContentProviderBackupState {

    private ParcelFileDescriptor inputFileDescriptor;

    private ParcelFileDescriptor outputFileDescriptor;

    private InputStream inputStream;

    private ZipOutputStream outputStream;

    private long bytesTransferred;

    private byte[] buffer;

    private String packageName;

    private int packageIndex;

    ParcelFileDescriptor getInputFileDescriptor() {
        return inputFileDescriptor;
    }

    void setInputFileDescriptor(ParcelFileDescriptor inputFileDescriptor) {
        this.inputFileDescriptor = inputFileDescriptor;
    }

    ParcelFileDescriptor getOutputFileDescriptor() {
        return outputFileDescriptor;
    }

    void setOutputFileDescriptor(ParcelFileDescriptor outputFileDescriptor) {
        this.outputFileDescriptor = outputFileDescriptor;
    }

    InputStream getInputStream() {
        return inputStream;
    }

    void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    ZipOutputStream getOutputStream() {
        return outputStream;
    }

    void setOutputStream(ZipOutputStream outputStream) {
        this.outputStream = outputStream;
    }

    long getBytesTransferred() {
        return bytesTransferred;
    }

    void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    String getPackageName() {
        return packageName;
    }

    void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    int getPackageIndex() {
        return packageIndex;
    }

    void setPackageIndex(int packageIndex) {
        this.packageIndex = packageIndex;
    }

    byte[] getBuffer() {
        return buffer;
    }

    void setBuffer(byte[] buffer) {
        this.buffer = buffer;
    }
}
