package org.calyxos.backup.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.api.mediaItems
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.db.StoredUri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal fun Uri.toStoredUri(): StoredUri = StoredUri(this)

internal fun Uri.getDocumentPath(): String? {
    return lastPathSegment?.split(':')?.getOrNull(1)
}

internal fun Uri.getVolume(): String? {
    val volume = lastPathSegment?.split(':')?.getOrNull(0)
    return if (volume == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else volume
}

@Throws(IOException::class)
public fun Uri.openInputStream(contentResolver: ContentResolver): InputStream {
    return try {
        contentResolver.openInputStream(this)
    } catch (e: IllegalArgumentException) {
        // This is necessary, because contrary to the documentation, files that have been deleted
        // after we retrieved their Uri, will throw an IllegalArgumentException
        throw IOException(e)
    } ?: throw IOException("Stream for $this returned null")
}

@Throws(IOException::class)
public fun Uri.openOutputStream(contentResolver: ContentResolver): OutputStream {
    return try {
        contentResolver.openOutputStream(this, "wt")
    } catch (e: IllegalArgumentException) {
        // This is necessary, because contrary to the documentation, files that have been deleted
        // after we retrieved their Uri, will throw an IllegalArgumentException
        throw IOException(e)
    } ?: throw IOException("Stream for $this returned null")
}

internal fun Uri.getMediaType(): MediaType? {
    val str = toString()
    for (item in mediaItems) {
        if (str.startsWith(item.contentUri.toString())) return item
    }
    return null
}

internal fun Uri.getBackupMediaType(): BackupMediaFile.MediaType? {
    return getMediaType()?.backupType
}
