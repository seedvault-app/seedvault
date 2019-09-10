package com.stevesoltys.backup.transport.restore

import com.stevesoltys.backup.metadata.EncryptedBackupMetadata

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
     * Get the identifying token of the backup set currently being stored from this device.
     * This is used in the case of applications wishing to restore their last-known-good data.
     *
     * @return A token that can be used for restore,
     * or 0 if there is no backup set available corresponding to the current device state.
     */
    fun getCurrentRestoreSet(): Long

}
