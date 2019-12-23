package com.stevesoltys.seedvault.transport.restore

import android.net.Uri
import androidx.annotation.WorkerThread
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

    /**
     * Searches if there's really a backup available in the given location.
     * Returns true if at least one was found and false otherwise.
     *
     * FIXME: Passing a Uri is maybe too plugin-specific?
     */
    @WorkerThread
    fun hasBackup(uri: Uri): Boolean

}
