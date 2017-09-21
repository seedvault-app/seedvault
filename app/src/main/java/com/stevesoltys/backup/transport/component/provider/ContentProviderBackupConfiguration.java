package com.stevesoltys.backup.transport.component.provider;

import android.content.Context;
import android.net.Uri;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupConfiguration {

    public static final String FULL_BACKUP_DIRECTORY = "full/";

    public static final String INCREMENTAL_BACKUP_DIRECTORY = "incr/";

    private final Context context;

    private final Uri uri;

    private final long backupSizeQuota;

    private final int packageCount;

    public ContentProviderBackupConfiguration(Context context, Uri uri, long backupSizeQuota, int packageCount) {
        this.context = context;
        this.uri = uri;
        this.backupSizeQuota = backupSizeQuota;
        this.packageCount = packageCount;
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
        return packageCount;
    }

}
