package com.stevesoltys.backup.transport.component.provider;

import android.content.Context;
import android.net.Uri;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupConfigurationBuilder {

    private Context context;

    private Uri outputUri;

    private String[] packages;

    private long backupSizeQuota = Long.MAX_VALUE;

    public ContentProviderBackupConfiguration build() {

        if(context == null) {
            throw new IllegalArgumentException("Context must be set.");

        } else if (outputUri == null) {
            throw new IllegalArgumentException("Output URI must be set.");

        } else if(packages == null) {
            throw new IllegalArgumentException("Package list must be set.");
        }

        return new ContentProviderBackupConfiguration(context, outputUri, packages, backupSizeQuota);
    }

    public ContentProviderBackupConfigurationBuilder setContext(Context context) {
        this.context = context;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setOutputUri(Uri outputUri) {
        this.outputUri = outputUri;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setPackages(String[] packages) {
        this.packages = packages;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setBackupSizeQuota(long backupSizeQuota) {
        this.backupSizeQuota = backupSizeQuota;
        return this;
    }
}
