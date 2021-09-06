package org.calyxos.backup.storage.api

import android.provider.MediaStore

public interface BackupFile {
    public val path: String

    /**
     * empty string for [MediaStore.VOLUME_EXTERNAL_PRIMARY]
     */
    public val volume: String
    public val size: Long
    public val lastModified: Long?
}
