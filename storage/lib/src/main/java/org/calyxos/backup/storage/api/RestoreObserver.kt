package org.calyxos.backup.storage.api

public interface RestoreObserver {
    public fun onRestoreStart(numFiles: Int, totalSize: Long)
    public fun onFileRestored(file: BackupFile, bytesWritten: Long, tag: String)

    /**
     * Called when a file failed to restore.
     * You might want to inform the user about this.
     * The exception already gets logged.
     */
    public fun onFileRestoreError(file: BackupFile, e: Exception)

    public fun onRestoreComplete(restoreDuration: Long)
}
