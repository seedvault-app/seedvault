package com.stevesoltys.seedvault.plugins.saf

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract.*
import android.provider.DocumentsContract.Document.*
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.Storage
import libcore.io.IoUtils.closeQuietly
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit.MINUTES

const val DIRECTORY_ROOT = ".AndroidBackup"
const val DIRECTORY_FULL_BACKUP = "full"
const val DIRECTORY_KEY_VALUE_BACKUP = "kv"
const val FILE_BACKUP_METADATA = ".backup.metadata"
const val FILE_NO_MEDIA = ".nomedia"
private const val MIME_TYPE = "application/octet-stream"

private val TAG = DocumentsStorage::class.java.simpleName

internal class DocumentsStorage(
        private val context: Context,
        private val settingsManager: SettingsManager) {

    private val storage: Storage? = settingsManager.getStorage()
    private val token: Long = settingsManager.getBackupToken()

    internal val rootBackupDir: DocumentFile? by lazy {
        val parent = storage?.getDocumentFile(context) ?: return@lazy null
        try {
            val rootDir = parent.createOrGetDirectory(DIRECTORY_ROOT)
            // create .nomedia file to prevent Android's MediaScanner from trying to index the backup
            rootDir.createOrGetFile(FILE_NO_MEDIA)
            rootDir
        } catch (e: IOException) {
            Log.e(TAG, "Error creating root backup dir.", e)
            null
        }
    }

    private val currentToken: Long by lazy {
        if (token != 0L) token
        else settingsManager.getAndSaveNewBackupToken().apply {
            Log.d(TAG, "Using a fresh backup token: $this")
        }
    }

    private val currentSetDir: DocumentFile? by lazy {
        val currentSetName = currentToken.toString()
        try {
            rootBackupDir?.createOrGetDirectory(currentSetName)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating current restore set dir.", e)
            null
        }
    }

    val currentFullBackupDir: DocumentFile? by lazy {
        try {
            currentSetDir?.createOrGetDirectory(DIRECTORY_FULL_BACKUP)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating full backup dir.", e)
            null
        }
    }

    val currentKvBackupDir: DocumentFile? by lazy {
        try {
            currentSetDir?.createOrGetDirectory(DIRECTORY_KEY_VALUE_BACKUP)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating K/V backup dir.", e)
            null
        }
    }

    fun getAuthority(): String? = storage?.uri?.authority

    fun getSetDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentSetDir
        return rootBackupDir?.findFile(token.toString())
    }

    fun getKVBackupDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentKvBackupDir ?: throw IOException()
        return getSetDir(token)?.findFile(DIRECTORY_KEY_VALUE_BACKUP)
    }

    @Throws(IOException::class)
    fun getOrCreateKVBackupDir(token: Long = currentToken): DocumentFile {
        if (token == currentToken) return currentKvBackupDir ?: throw IOException()
        val setDir = getSetDir(token) ?: throw IOException()
        return setDir.createOrGetDirectory(DIRECTORY_KEY_VALUE_BACKUP)
    }

    fun getFullBackupDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentFullBackupDir ?: throw IOException()
        return getSetDir(token)?.findFile(DIRECTORY_FULL_BACKUP)
    }

    @Throws(IOException::class)
    fun getInputStream(file: DocumentFile): InputStream {
        return context.contentResolver.openInputStream(file.uri) ?: throw IOException()
    }

    @Throws(IOException::class)
    fun getOutputStream(file: DocumentFile): OutputStream {
        return context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException()
    }

}

@Throws(IOException::class)
fun DocumentFile.createOrGetFile(name: String, mimeType: String = MIME_TYPE): DocumentFile {
    return findFile(name) ?: createFile(mimeType, name) ?: throw IOException()
}

@Throws(IOException::class)
fun DocumentFile.createOrGetDirectory(name: String): DocumentFile {
    return findFile(name) ?: createDirectory(name) ?: throw IOException()
}

@Throws(IOException::class)
fun DocumentFile.deleteContents() {
    for (file in listFiles()) file.delete()
}

fun DocumentFile.assertRightFile(packageInfo: PackageInfo) {
    if (name != packageInfo.packageName) throw AssertionError()
}

/**
 * Works like [DocumentFile.listFiles] except that it waits until the DocumentProvider has a result.
 * This prevents getting an empty list even though there are children to be listed.
 */
@Throws(IOException::class)
fun DocumentFile.listFilesBlocking(context: Context): ArrayList<DocumentFile> {
    val resolver = context.contentResolver
    val childrenUri = buildChildDocumentsUriUsingTree(uri, getDocumentId(uri))
    val projection = arrayOf(COLUMN_DOCUMENT_ID, COLUMN_MIME_TYPE)
    val result = ArrayList<DocumentFile>()

    @SuppressLint("Recycle")  // gets closed in with(), only earlier exit when null
    var cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException()
    val loading = cursor.extras.getBoolean(EXTRA_LOADING, false)
    if (loading) {
        Log.d(TAG, "Wait for children to get loaded...")
        var loaded = false
        cursor.registerContentObserver(object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "Children loaded. Continue...")
                loaded = true
            }
        })
        val timeout = MINUTES.toMillis(2)
        var time = 0
        while (!loaded && time < timeout) {
            Thread.sleep(50)
            time += 50
        }
        if (time >= timeout) Log.w(TAG, "Timed out while waiting for children to load")
        closeQuietly(cursor)
        // do a new query after content was loaded
        @SuppressLint("Recycle")  // gets closed after with block
        cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: throw IOException()
    }
    with(cursor) {
        while (moveToNext()) {
            val documentId = getString(0)
            val isDirectory = getString(1) == MIME_TYPE_DIR
            val file = if (isDirectory) {
                val treeUri = buildTreeDocumentUri(uri.authority, documentId)
                DocumentFile.fromTreeUri(context, treeUri)!!
            } else {
                val documentUri = buildDocumentUriUsingTree(uri, documentId)
                DocumentFile.fromSingleUri(context, documentUri)!!
            }
            result.add(file)
        }
    }
    return result
}

fun DocumentFile.findFileBlocking(context: Context, displayName: String): DocumentFile? {
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
