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

    private final long backupSizeQuota;

    private final Set<String> packages;

    private final String fullBackupDirectory;

    private final String incrementalBackupDirectory;

    ContentProviderBackupConfiguration(Context context, Uri uri, Set<String> packages, long backupSizeQuota,
                                       String fullBackupDirectory, String incrementalBackupDirectory) {
        this.context = context;
        this.uri = uri;
        this.packages = packages;
        this.backupSizeQuota = backupSizeQuota;
        this.fullBackupDirectory = fullBackupDirectory;
        this.incrementalBackupDirectory = incrementalBackupDirectory;
    }

    public Context getContext() {
        return context;
    }

    public Uri getUri() {
        return uri;
    }

    public long getBackupSizeQuota() {
        return backupSizeQuota;
    }

    public int getPackageCount() {
        return packages.size();
    }

    public String getFullBackupDirectory() {
        return fullBackupDirectory;
    }

    public String getIncrementalBackupDirectory() {
        return incrementalBackupDirectory;
    }
}
