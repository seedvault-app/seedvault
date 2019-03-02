package com.stevesoltys.backup.transport.component.provider;

import android.content.Context;
import android.net.Uri;
import com.android.internal.util.Preconditions;

import java.util.Set;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupConfigurationBuilder {

    public static final String DEFAULT_FULL_BACKUP_DIRECTORY = "full/";

    public static final String DEFAULT_INCREMENTAL_BACKUP_DIRECTORY = "incr/";

    private Context context;

    private Uri outputUri;

    private Set<String> packages;

    private String password;

    private long backupSizeQuota = Long.MAX_VALUE;

    private String incrementalBackupDirectory = DEFAULT_INCREMENTAL_BACKUP_DIRECTORY;

    private String fullBackupDirectory = DEFAULT_FULL_BACKUP_DIRECTORY;

    public ContentProviderBackupConfiguration build() {
        Preconditions.checkState(context != null, "Context must be set.");
        Preconditions.checkState(outputUri != null, "Output URI must be set.");
        Preconditions.checkState(packages != null, "Package list must be set.");
        Preconditions.checkState(incrementalBackupDirectory != null, "Incremental backup directory must be set.");
        Preconditions.checkState(fullBackupDirectory != null, "Full backup directory must be set.");

        return new ContentProviderBackupConfiguration(context, outputUri, packages, password, backupSizeQuota,
                fullBackupDirectory, incrementalBackupDirectory);
    }

    public ContentProviderBackupConfigurationBuilder setBackupSizeQuota(long backupSizeQuota) {
        this.backupSizeQuota = backupSizeQuota;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setContext(Context context) {
        this.context = context;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setFullBackupDirectory(String fullBackupDirectory) {
        this.fullBackupDirectory = fullBackupDirectory;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setIncrementalBackupDirectory(String incrementalBackupDirectory) {
        this.incrementalBackupDirectory = incrementalBackupDirectory;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setOutputUri(Uri outputUri) {
        this.outputUri = outputUri;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setPackages(Set<String> packages) {
        this.packages = packages;
        return this;
    }

    public ContentProviderBackupConfigurationBuilder setPassword(String password) {
        this.password = password;
        return this;
    }
}
