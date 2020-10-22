package com.stevesoltys.seedvault.transport.restore

import android.net.Uri
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import java.io.IOException
import java.io.InputStream

interface RestorePlugin {

    val kvRestorePlugin: KVRestorePlugin

    val fullRestorePlugin: FullRestorePlugin

    /**
     * Get the set of all backups currently available for restore.
     *
     * @return metadata for the set of restore images available,
     * or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableBackups(): Sequence<EncryptedBackupMetadata>?

    /**
     * Searches if there's really a backup available in the given location.
     * Returns true if at least one was found and false otherwise.
     *
     * FIXME: Passing a Uri is maybe too plugin-specific?
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun hasBackup(uri: Uri): Boolean

    /**
     * Returns an [InputStream] for the given token, for reading an APK that is to be restored.
     */
    @Throws(IOException::class)
    suspend fun getApkInputStream(token: Long, packageName: String, suffix: String): InputStream

}
