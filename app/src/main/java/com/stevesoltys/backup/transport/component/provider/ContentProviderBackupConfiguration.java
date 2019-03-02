package com.stevesoltys.backup.transport.component.provider;

import android.content.Context;
import android.net.Uri;

import java.util.Set;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupConfiguration {

    private final Context context;

    private final Uri uri;

    private final String password;

    private final long backupSizeQuota;

    private final Set<String> packages;

    private final String fullBackupDirectory;

    private final String incrementalBackupDirectory;

    ContentProviderBackupConfiguration(Context context, Uri uri, Set<String> packages, String password,
                                       long backupSizeQuota, String fullBackupDirectory,
                                       String incrementalBackupDirectory) {
        this.context = context;
        this.uri = uri;
        this.packages = packages;
        this.password = password;
        this.backupSizeQuota = backupSizeQuota;
        this.fullBackupDirectory = fullBackupDirectory;
        this.incrementalBackupDirectory = incrementalBackupDirectory;
    }

    public long getBackupSizeQuota() {
        return backupSizeQuota;
    }

    public Context getContext() {
        return context;
    }

    public String getFullBackupDirectory() {
        return fullBackupDirectory;
    }

    public String getIncrementalBackupDirectory() {
        return incrementalBackupDirectory;
    }

    public int getPackageCount() {
        return packages.size();
    }

    public String getPassword() {
        return password;
    }

    public Uri getUri() {
        return uri;
    }
}
