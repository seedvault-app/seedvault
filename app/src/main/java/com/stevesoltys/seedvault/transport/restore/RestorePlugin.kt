package com.stevesoltys.seedvault.transport.restore

import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata

interface RestorePlugin {

    val kvRestorePlugin: KVRestorePlugin

    val fullRestorePlugin: FullRestorePlugin

    /**
     * Get the set of all backups currently available for restore.
     *
     * @return metadata for the set of restore images available,
     * or null if an error occurred (the attempt should be rescheduled).
     **/
    fun getAvailableBackups(): Sequence<EncryptedBackupMetadata>?

}
