package com.stevesoltys.backup.transport.restore

import android.app.backup.RestoreSet

interface RestorePlugin {

    val kvRestorePlugin: KVRestorePlugin

    val fullRestorePlugin: FullRestorePlugin

    /**
     * Get the set of all backups currently available for restore.
     *
     * @return Descriptions of the set of restore images available for this device,
     * or null if an error occurred (the attempt should be rescheduled).
     **/
    fun getAvailableRestoreSets(): Array<RestoreSet>?

    /**
     * Get the identifying token of the backup set currently being stored from this device.
     * This is used in the case of applications wishing to restore their last-known-good data.
     *
     * @return A token that can be used for restore,
     * or 0 if there is no backup set available corresponding to the current device state.
     */
    fun getCurrentRestoreSet(): Long

}
