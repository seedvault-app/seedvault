/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.seedvault.core.backends.saf.getDocumentPath

// hidden in DocumentsContract
public const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY: String =
    "com.android.externalstorage.documents"

public val mediaItems: List<MediaType> = listOf(
    MediaType.Images,
    MediaType.Video,
    MediaType.Audio,
    MediaType.Downloads
)

public val mediaUris: List<Uri> = listOf(
    MediaType.Images.contentUri,
    MediaType.Video.contentUri,
    MediaType.Audio.contentUri,
    MediaType.Downloads.contentUri
)

public sealed class BackupContentType(
    @DrawableRes
    public val drawableRes: Int,
) {
    public object Custom : BackupContentType(R.drawable.ic_folder) {
        public fun getName(uri: Uri): String {
            val path = uri.getDocumentPath()!!
            return path.ifBlank { "/" }
        }
    }
}

public sealed class MediaType(
    public val contentUri: Uri,
    @StringRes
    public val nameRes: Int,
    @DrawableRes
    drawableRes: Int,
    public val backupType: BackupMediaFile.MediaType,
) : BackupContentType(drawableRes) {
    public object Images : MediaType(
        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        nameRes = R.string.content_images,
        drawableRes = R.drawable.ic_photo_library,
        backupType = BackupMediaFile.MediaType.IMAGES,
    )

    public object Video : MediaType(
        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        nameRes = R.string.content_videos,
        drawableRes = R.drawable.ic_video_library,
        backupType = BackupMediaFile.MediaType.VIDEO,
    )

    public object Audio : MediaType(
        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        nameRes = R.string.content_audio,
        drawableRes = R.drawable.ic_music_library,
        backupType = BackupMediaFile.MediaType.AUDIO,
    )

    public object Downloads : MediaType(
        contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        nameRes = R.string.content_downloads,
        drawableRes = R.drawable.ic_download_library,
        backupType = BackupMediaFile.MediaType.DOWNLOADS,
    )

    internal companion object {
        fun fromBackupMediaType(type: BackupMediaFile.MediaType): MediaType = when (type) {
            BackupMediaFile.MediaType.IMAGES -> Images
            BackupMediaFile.MediaType.VIDEO -> Video
            BackupMediaFile.MediaType.AUDIO -> Audio
            BackupMediaFile.MediaType.DOWNLOADS -> Downloads
            else -> throw AssertionError("Unrecognized media type: $type")
        }
    }
}
