package com.stevesoltys.backup.transport.component.provider;

import android.content.Context;
import android.net.Uri;

/**
 * @author Steve Soltys
 */
public class ContentProviderBackupConfigurationBuilder {

    private static final long DEFAULT_BACKUP_SIZE_QUOTA = Long.MAX_VALUE;

    public static ContentProviderBackupConfiguration buildDefaultConfiguration(Context context, Uri outputUri,
                                                                               int packageCount) {
        return new ContentProviderBackupConfiguration(context,  outputUri, DEFAULT_BACKUP_SIZE_QUOTA, packageCount);
    }

}
