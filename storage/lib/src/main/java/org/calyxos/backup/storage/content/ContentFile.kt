/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.content

import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.backup.BackupDocumentFile
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.db.CachedFile
import org.calyxos.backup.storage.getBackupMediaType

internal sealed class ContentFile : BackupFile {
    abstract val uri: Uri
    abstract val dirPath: String
    abstract val fileName: String
    abstract override val size: Long
    abstract override val volume: String
    abstract override val lastModified: Long?
    open val generationModified: Long? = null
    override val path: String get() = "$dirPath/$fileName"

    /**
     * We use this to identify duplicate files and prevent them from getting backed up.
     * Unfortunately, MediaStoreProvider and ExternalStorageProvider have different ideas about
     * file sizes and lastModified timestamps, so that we can only use [volume] and [path].
     */
    internal val id get() = "$volume:$path"

    internal fun toCachedFile(chunkIds: List<String>, zipIndex: Int? = null) = CachedFile(
        uri = uri,
        size = size,
        lastModified = lastModified,
        generationModified = generationModified,
        chunks = chunkIds,
        zipIndex = zipIndex,
        lastSeen = System.currentTimeMillis(),
    )

    /**
     * Returns an [Uri] that returns the original file for backup.
     * http://aosp.opersys.com/xref/android-11.0.0_r8/xref/packages/providers/MediaProvider/apex/framework/java/android/provider/MediaStore.java#751
     */
    internal fun getOriginalUri(hasMediaAccessPerm: Boolean): Uri = when (this) {
        is MediaFile -> if (hasMediaAccessPerm) MediaStore.setRequireOriginal(uri) else uri
        is DocFile -> uri
    }

    internal open fun hasNotChanged(cachedFile: CachedFile?): Boolean {
        if (cachedFile == null) return false
        val sizeUnchanged = size == cachedFile.size
        val notModified = lastModified != null && lastModified == cachedFile.lastModified
        if (!sizeUnchanged || !notModified) {
            Log.d("ContentFile", "$path has changed, because")
            if (!sizeUnchanged) Log.d("ContentFile", "  size was ${cachedFile.size}, now is $size.")
            if (!notModified) Log.d(
                "ContentFile",
                "  lastModified was ${cachedFile.lastModified}, now is $lastModified."
            )
        }
        return sizeUnchanged && notModified
    }
}

internal data class MediaFile(
    override val uri: Uri,
    val dir: String,
    override val fileName: String,
    val dateModified: Long?,
    override val generationModified: Long?,
    override val size: Long,
    override val volume: String,
    val isFavorite: Boolean,
    val ownerPackageName: String?,
) : ContentFile() {
    override val dirPath: String = dir.trimEnd('/')
    override val lastModified: Long? = dateModified?.times(1000)

    override fun hasNotChanged(cachedFile: CachedFile?): Boolean {
        if (cachedFile == null) return false
        val generationMod = generationModified != cachedFile.generationModified
        if (generationMod) {
            Log.e(
                "TEST", "$path has changed, because generation was " +
                    "${cachedFile.generationModified}, now is $generationModified."
            )
        }
        return super.hasNotChanged(cachedFile) && !generationMod
    }

    internal fun toBackupFile(chunkIds: List<String>, zipIndex: Int? = null): BackupMediaFile {
        val type = uri.getBackupMediaType() ?: error("Could not get MediaType for $uri")
        val builder = BackupMediaFile.newBuilder()
            .setType(type)
            .setPath(dirPath)
            .setName(fileName)
            .setSize(size)
            .addAllChunkIds(chunkIds)
            .setVolume(if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "" else volume)
        if (lastModified != null) {
            builder.lastModified = lastModified
        }
        if (zipIndex != null) {
            builder.zipIndex = zipIndex
        }
        return builder.build()
    }
}

internal data class DocFile(
    override val uri: Uri,
    override val dirPath: String,
    override val fileName: String,
    override val lastModified: Long?,
    override val size: Long,
    override val volume: String,
) : ContentFile() {
    internal fun toBackupFile(chunkIds: List<String>, zipIndex: Int? = null): BackupDocumentFile {
        val builder = BackupDocumentFile.newBuilder()
            .setPath(dirPath)
            .setName(fileName)
            .setSize(size)
            .addAllChunkIds(chunkIds)
        if (volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            builder.volume = volume
        }
        if (lastModified != null) {
            builder.lastModified = lastModified
        }
        if (zipIndex != null) {
            builder.zipIndex = zipIndex
        }
        return builder.build()
    }
}
