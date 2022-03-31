@file:Suppress("BlockingMethodInNonBlockingContext")

package com.stevesoltys.seedvault.plugins.saf

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
import com.stevesoltys.seedvault.getSystemContext
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.Storage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

const val DIRECTORY_ROOT = ".SeedVaultAndroidBackup"

@Deprecated("")
const val DIRECTORY_FULL_BACKUP = "full"

@Deprecated("")
const val DIRECTORY_KEY_VALUE_BACKUP = "kv"
const val FILE_BACKUP_METADATA = ".backup.metadata"
const val FILE_NO_MEDIA = ".nomedia"
const val MIME_TYPE = "application/octet-stream"

private val TAG = DocumentsStorage::class.java.simpleName

internal class DocumentsStorage(
    private val appContext: Context,
    private val settingsManager: SettingsManager,
) {
    internal var storage: Storage? = null
        get() {
            if (field == null) field = settingsManager.getStorage()
            return field
        }

    /**
     * Attention: This context might be from a different user. Use with care.
     */
    private val context: Context
        get() = appContext.getSystemContext {
            storage?.isUsb == true
        }
    private val contentResolver: ContentResolver get() = context.contentResolver

    internal var rootBackupDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                val parent = storage?.getDocumentFile(context)
                    ?: return@runBlocking null
                field = try {
                    parent.createOrGetDirectory(context, DIRECTORY_ROOT).apply {
                        // create .nomedia file to prevent Android's MediaScanner
                        // from trying to index the backup
                        createOrGetFile(context, FILE_NO_MEDIA)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating root backup dir.", e)
                    null
                }
            }
            field
        }

    private var currentToken: Long? = null
        get() {
            if (field == null) field = settingsManager.getToken()
            return field
        }

    var currentSetDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                if (currentToken == 0L) return@runBlocking null
                field = try {
                    rootBackupDir?.createOrGetDirectory(context, currentToken.toString())
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating current restore set dir.", e)
                    null
                }
            }
            field
        }
        private set

    /**
     * Resets this storage abstraction, forcing it to re-fetch cached values on next access.
     */
    fun reset(newToken: Long?) {
        storage = null
        currentToken = newToken
        rootBackupDir = null
        currentSetDir = null
    }

    fun getAuthority(): String? = storage?.uri?.authority

    @Throws(IOException::class)
    suspend fun getSetDir(token: Long = currentToken ?: error("no token")): DocumentFile? {
        if (token == currentToken) return currentSetDir
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
        return contentResolver.openInputStream(file.uri) ?: throw IOException()
    }

    @Throws(IOException::class)
    fun getOutputStream(file: DocumentFile): OutputStream {
        return contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException()
    }

}

/**
 * Checks if a file exists and if not, creates it.
 *
 * If we were trying to create it right away, some providers create "filename (1)".
 */
@Throws(IOException::class)
internal suspend fun DocumentFile.createOrGetFile(
    context: Context,
    name: String,
    mimeType: String = MIME_TYPE,
): DocumentFile {
    return try {
        findFileBlocking(context, name) ?: createFile(mimeType, name)?.apply {
            if (this.name != name) {
                throw IOException("File named ${this.name}, but should be $name")
            }
        } ?: throw IOException()
    } catch (e: IllegalArgumentException) {
        // Can be thrown by FileSystemProvider#isChildDocument() when flash drive is not plugged-in
        // http://aosp.opersys.com/xref/android-11.0.0_r8/xref/frameworks/base/core/java/com/android/internal/content/FileSystemProvider.java#135
        throw IOException(e)
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

@Throws(IOException::class)
suspend fun DocumentFile.deleteContents(context: Context) {
    for (file in listFilesBlocking(context)) file.delete()
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
 * An extremely dirty reflection hack to instantiate a TreeDocumentFile with a parent.
 *
 * All other public ways to get a TreeDocumentFile only work from [Uri]s
 * (e.g. [DocumentFile.fromTreeUri]) and always set parent to null.
 *
 * We have a test for this method to ensure CI will alert us when this reflection breaks.
 * Also, [DocumentFile] is part of AndroidX, so we control the dependency and notice when it fails.
 */
@VisibleForTesting
internal fun getTreeDocumentFile(parent: DocumentFile, context: Context, uri: Uri): DocumentFile {
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
suspend fun DocumentFile.findFileBlocking(context: Context, displayName: String): DocumentFile? {
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
internal suspend fun getLoadedCursor(timeout: Long = 15_000, query: () -> Cursor?) =
    withTimeout(timeout) {
        suspendCancellableCoroutine<Cursor> { cont ->
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
