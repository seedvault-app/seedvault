package com.stevesoltys.backup.transport.component.provider;

/**
 * @author Steve Soltys
 */
public interface ContentProviderBackupConstants {

    String SALT_FILE_PATH = "salt";

    String DEFAULT_FULL_BACKUP_DIRECTORY = "full/";

    String DEFAULT_INCREMENTAL_BACKUP_DIRECTORY = "incr/";

    long DEFAULT_BACKUP_QUOTA = Long.MAX_VALUE;

}
