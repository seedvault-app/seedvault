/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.EXTRA_LOADING
import android.provider.DocumentsContract.buildChildDocumentsUriUsingTree
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.getDocumentId
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.calyxos.seedvault.core.backends.Constants.MIME_TYPE
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

private const val TAG = "SafHelper"

@Throws(IOException::class)
public fun DocumentFile.getInputStream(contentResolver: ContentResolver): InputStream {
    return uri.openInputStream(contentResolver)
}

@Throws(IOException::class)
public fun DocumentFile.getOutputStream(contentResolver: ContentResolver): OutputStream {
    return uri.openOutputStream(contentResolver)
}

/**
 * Checks if a file exists and if not, creates it.
 *
 * If we were trying to create it right away, some providers create "filename (1)".
 */
@Throws(IOException::class)
internal suspend fun DocumentFile.getOrCreateFile(context: Context, name: String): DocumentFile {
    return try {
        findFileBlocking(context, name) ?: createFileOrThrow(name, MIME_TYPE)
    } catch (e: Exception) {
        // SAF can throw all sorts of exceptions, so wrap it in IOException.
        // E.g. IllegalArgumentException can be thrown by FileSystemProvider#isChildDocument()
        // when flash drive is not plugged-in:
        // http://aosp.opersys.com/xref/android-11.0.0_r8/xref/frameworks/base/core/java/com/android/internal/content/FileSystemProvider.java#135
        if (e is IOException) throw e
        else throw IOException(e)
    }
}

@Throws(IOException::class)
internal fun DocumentFile.createFileOrThrow(
    name: String,
    mimeType: String = MIME_TYPE,
): DocumentFile {
    val file = createFile(mimeType, name) ?: throw IOException("Unable to create file: $name")
    if (file.name != name) {
        file.delete()
        if (file.name == null) { // this happens when file existed already
            // try to find the original file we were looking for
            val foundFile = findFile(name)
            if (foundFile?.name == name) return foundFile
        }
        throw IOException("Wanted to create $name, but got ${file.name}")
    }
    return file
}

/**
 * Checks if a directory already exists and if not, creates it.
 */
@Throws(IOException::class)
public suspend fun DocumentFile.getOrCreateDirectory(context: Context, name: String): DocumentFile {
    return findFileBlocking(context, name) ?: createDirectoryOrThrow(name)
}

@Throws(IOException::class)
public fun DocumentFile.createDirectoryOrThrow(name: String): DocumentFile {
    val directory = createDirectory(name)
        ?: throw IOException("Unable to create directory: $name")
    if (directory.name != name) {
        directory.delete()
        throw IOException("Wanted to directory $name, but got ${directory.name}")
    }
    return directory
}

/**
 * Works like [DocumentFile.listFiles] except
 * that it waits until the DocumentProvider has a result.
 * This prevents getting an empty list even though there are children to be listed.
 */
@Throws(IOException::class)
public suspend fun DocumentFile.listFilesBlocking(context: Context): List<DocumentFile> {
    val resolver = context.contentResolver
    val childrenUri = buildChildDocumentsUriUsingTree(uri, getDocumentId(uri))
    val projection = arrayOf(COLUMN_DOCUMENT_ID)
    val result = ArrayList<DocumentFile>()

    try {
        getLoadedCursor {
            resolver.query(childrenUri, projection, null, null, null)
        }
    } catch (e: TimeoutCancellationException) {
        throw IOException(e)
    }.use { cursor ->
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(0)
            val documentUri = buildDocumentUriUsingTree(uri, documentId)
            result.add(getTreeDocumentFile(this, context, documentUri))
        }
    }
    return result
}

/**
 * An extremely dirty reflection hack to instantiate a TreeDocumentFile with a parent.
 *
 * All other public ways to get a TreeDocumentFile only work from [Uri]s
 * (e.g. [DocumentFile.fromTreeUri]) and always set parent to null.
 *
 * We have a test for this method to ensure CI will alert us when this reflection breaks.
 * Also, [DocumentFile] is part of AndroidX, so we control the dependency and notice when it fails.
 */
@VisibleForTesting
@SuppressLint("CheckedExceptions")
public fun getTreeDocumentFile(
    parent: DocumentFile,
    context: Context,
    uri: Uri,
): DocumentFile {
    @SuppressWarnings("MagicNumber")
    val constructor = parent.javaClass.declaredConstructors.find {
        it.name == "androidx.documentfile.provider.TreeDocumentFile" && it.parameterCount == 3
    }
    check(constructor != null) { "Could not find constructor for TreeDocumentFile" }
    constructor.isAccessible = true
    return constructor.newInstance(parent, context, uri) as DocumentFile
}

/**
 * Same as [DocumentFile.findFile] only that it re-queries when the first result was stale.
 *
 * Most documents providers including Nextcloud are listing the full directory content
 * when querying for a specific file in a directory,
 * so there is no point in trying to optimize the query by not listing all children.
 */
public suspend fun DocumentFile.findFileBlocking(
    context: Context,
    displayName: String,
): DocumentFile? {
    val files = try {
        listFilesBlocking(context)
    } catch (e: IOException) {
        Log.e(TAG, "Error finding file blocking", e)
        return null
    }
    for (doc in files) {
        if (displayName == doc.name) return doc
    }
    return null
}

/**
 * Returns a cursor for the given query while ensuring that the cursor was loaded.
 *
 * When the SAF backend is a cloud storage provider (e.g. Nextcloud),
 * it can happen that the query returns an outdated (e.g. empty) cursor
 * which will only be updated in response to this query.
 *
 * See: https://commonsware.com/blog/2019/12/14/scoped-storage-stories-listfiles-woe.html
 *
 * This method uses a [suspendCancellableCoroutine] to wait for the result of a [ContentObserver]
 * registered on the cursor in case the cursor is still loading ([EXTRA_LOADING]).
 * If the cursor is not loading, it will be returned right away.
 *
 * @param timeout an optional time-out in milliseconds
 * @throws TimeoutCancellationException if there was no result before the time-out
 * @throws IOException if the query returns null
 */
@VisibleForTesting
@Throws(IOException::class, TimeoutCancellationException::class)
public suspend fun getLoadedCursor(timeout: Long = 15_000, query: () -> Cursor?): Cursor =
    withTimeout(timeout) {
        suspendCancellableCoroutine { cont ->
            val cursor = query() ?: throw IOException()
            cont.invokeOnCancellation { cursor.close() }
            val loading = cursor.extras.getBoolean(EXTRA_LOADING, false)
            if (loading) {
                Log.d(TAG, "Wait for children to get loaded...")
                cursor.registerContentObserver(object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        Log.d(TAG, "Children loaded. Continue...")
                        cursor.close()
                        val newCursor = query()
                        if (newCursor == null) {
                            cont.cancel(IOException("query returned no results"))
                        } else cont.resume(newCursor)
                    }
                })
            } else {
                // not loading, return cursor right away
                cont.resume(cursor)
            }
        }
    }
