/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

public fun Uri.getDocumentPath(): String? {
    return lastPathSegment?.split(':')?.getOrNull(1)
}

public fun Uri.getVolume(): String? {
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
