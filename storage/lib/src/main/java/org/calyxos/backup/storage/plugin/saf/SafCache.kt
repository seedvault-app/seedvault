package org.calyxos.backup.storage.plugin.saf

import androidx.documentfile.provider.DocumentFile
import org.calyxos.backup.storage.api.StoredSnapshot

/**
 * Accessing files and attributes via SAF is costly.
 * This class caches them to speed up SAF related operations.
 */
internal class SafCache {

    /**
     * The folder for the current user ID (here "${ANDROID_ID}.sv").
     */
    var currentFolder: DocumentFile? = null

    /**
     * Folders containing chunks for backups of the current user ID.
     */
    val backupChunkFolders = HashMap<String, DocumentFile>(CHUNK_FOLDER_COUNT)

    /**
     * Folders containing chunks for restore of a chosen [StoredSnapshot].
     */
    val restoreChunkFolders = HashMap<String, DocumentFile>(CHUNK_FOLDER_COUNT)

    /**
     * Files for each [StoredSnapshot].
     */
    val snapshotFiles = HashMap<StoredSnapshot, DocumentFile>()

}
