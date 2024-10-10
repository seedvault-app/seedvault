/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
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
import com.stevesoltys.seedvault.getStorageContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.backends.saf.getTreeDocumentFile
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

@Deprecated("")
const val DIRECTORY_FULL_BACKUP = "full"

@Deprecated("")
const val DIRECTORY_KEY_VALUE_BACKUP = "kv"

private val TAG = DocumentsStorage::class.java.simpleName

internal class DocumentsStorage(
    private val appContext: Context,
    internal val safStorage: SafProperties,
) {

    /**
     * Attention: This context might be from a different user. Use with care.
     */
    private val context: Context get() = appContext.getStorageContext { safStorage.isUsb }
    private val contentResolver: ContentResolver get() = context.contentResolver

    private var rootBackupDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                val parent = safStorage.getDocumentFile(context)
                field = try {
                    parent.createOrGetDirectory(context, DIRECTORY_ROOT)
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating root backup dir.", e)
                    null
                }
            }
            field
        }

    @Throws(IOException::class)
    suspend fun getSetDir(token: Long): DocumentFile? {
        return rootBackupDir?.findFileBlocking(context, token.toString())
    }

    @Throws(IOException::class)
    @Suppress("Deprecation")
    @Deprecated("Use only for v0 restore")
    suspend fun getKVBackupDir(token: Long): DocumentFile? {
        return getSetDir(token)?.findFileBlocking(context, DIRECTORY_KEY_VALUE_BACKUP)
    }

    @Throws(IOException::class)
    @Suppress("Deprecation")
    @Deprecated("Use only for v0 restore")
    suspend fun getFullBackupDir(token: Long): DocumentFile? {
        return getSetDir(token)?.findFileBlocking(context, DIRECTORY_FULL_BACKUP)
    }

    @Throws(IOException::class)
    fun getInputStream(file: DocumentFile): InputStream {
        return try {
            contentResolver.openInputStream(file.uri) ?: throw IOException()
        } catch (e: Exception) {
            // SAF can throw all sorts of exceptions, so wrap it in IOException
            throw IOException(e)
        }
    }

}

/**
 * Checks if a directory already exists and if not, creates it.
 */
@Throws(IOException::class)
suspend fun DocumentFile.createOrGetDirectory(context: Context, name: String): DocumentFile {
    return findFileBlocking(context, name) ?: createDirectory(name)?.apply {
        if (this.name != name) {
            throw IOException("Directory named ${this.name}, but should be $name")
        }
    } ?: throw IOException()
}

fun DocumentFile.assertRightFile(packageInfo: PackageInfo) {
    if (name != packageInfo.packageName) {
        throw AssertionError("Expected ${packageInfo.packageName}, but got $name")
    }
}

/**
 * Works like [DocumentFile.listFiles] except that it waits until the DocumentProvider has a result.
 * This prevents getting an empty list even though there are children to be listed.
 */
@Throws(IOException::class)
suspend fun DocumentFile.listFilesBlocking(context: Context): List<DocumentFile> {
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
 * Same as [DocumentFile.findFile] only that it re-queries when the first result was stale.
 *
 * Most documents providers including Nextcloud are listing the full directory content
 * when querying for a specific file in a directory,
 * so there is no point in trying to optimize the query by not listing all children.
 */
suspend fun DocumentFile.findFileBlocking(context: Context, displayName: String): DocumentFile? {
    val files = try {
        listFilesBlocking(context)
    } catch (e: Exception) {
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
internal suspend fun getLoadedCursor(timeout: Long = 15_000, query: () -> Cursor?) =
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
                        if (newCursor == null) cont.cancel(IOException("query returned no results"))
                        else cont.resume(newCursor)
                    }
                })
            } else {
                // not loading, return cursor right away
                cont.resume(cursor)
            }
        }
    }
