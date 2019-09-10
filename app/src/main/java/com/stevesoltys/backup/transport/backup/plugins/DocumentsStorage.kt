package com.stevesoltys.backup.transport.backup.plugins

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.backup.transport.DEFAULT_RESTORE_SET_TOKEN
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

const val DIRECTORY_FULL_BACKUP = "full"
const val DIRECTORY_KEY_VALUE_BACKUP = "kv"
const val FILE_BACKUP_METADATA = ".backup.metadata"
private const val ROOT_DIR_NAME = ".AndroidBackup"
private const val NO_MEDIA = ".nomedia"
private const val MIME_TYPE = "application/octet-stream"

private val TAG = DocumentsStorage::class.java.simpleName

class DocumentsStorage(context: Context, parentFolder: Uri?, deviceName: String) {

    private val contentResolver = context.contentResolver

    internal val rootBackupDir: DocumentFile? by lazy {
        val folderUri = parentFolder ?: return@lazy null
        // [fromTreeUri] should only return null when SDK_INT < 21
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: throw AssertionError()
        try {
            val rootDir = parent.createOrGetDirectory(ROOT_DIR_NAME)
            // create .nomedia file to prevent Android's MediaScanner from trying to index the backup
            rootDir.createOrGetFile(NO_MEDIA)
            rootDir
        } catch (e: IOException) {
            Log.e(TAG, "Error creating root backup dir.", e)
            null
        }
    }

    private val deviceDir: DocumentFile? by lazy {
        try {
            rootBackupDir?.createOrGetDirectory(deviceName)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating current restore set dir.", e)
            null
        }
    }

    private val defaultSetDir: DocumentFile? by lazy {
        val currentSetName = DEFAULT_RESTORE_SET_TOKEN.toString()
        try {
            deviceDir?.createOrGetDirectory(currentSetName)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating current restore set dir.", e)
            null
        }
    }

    val defaultFullBackupDir: DocumentFile? by lazy {
        try {
            defaultSetDir?.createOrGetDirectory(DIRECTORY_FULL_BACKUP)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating full backup dir.", e)
            null
        }
    }

    val defaultKvBackupDir: DocumentFile? by lazy {
        try {
            defaultSetDir?.createOrGetDirectory(DIRECTORY_KEY_VALUE_BACKUP)
        } catch (e: IOException) {
            Log.e(TAG, "Error creating K/V backup dir.", e)
            null
        }
    }

    fun getSetDir(token: Long = DEFAULT_RESTORE_SET_TOKEN): DocumentFile? {
        if (token == DEFAULT_RESTORE_SET_TOKEN) return defaultSetDir
        return deviceDir?.findFile(token.toString())
    }

    fun getKVBackupDir(token: Long = DEFAULT_RESTORE_SET_TOKEN): DocumentFile? {
        if (token == DEFAULT_RESTORE_SET_TOKEN) return defaultKvBackupDir ?: throw IOException()
        return getSetDir(token)?.findFile(DIRECTORY_KEY_VALUE_BACKUP)
    }

    @Throws(IOException::class)
    fun getOrCreateKVBackupDir(token: Long = DEFAULT_RESTORE_SET_TOKEN): DocumentFile {
        if (token == DEFAULT_RESTORE_SET_TOKEN) return defaultKvBackupDir ?: throw IOException()
        val setDir = getSetDir(token) ?: throw IOException()
        return setDir.createOrGetDirectory(DIRECTORY_KEY_VALUE_BACKUP)
    }

    fun getFullBackupDir(token: Long = DEFAULT_RESTORE_SET_TOKEN): DocumentFile? {
        if (token == DEFAULT_RESTORE_SET_TOKEN) return defaultFullBackupDir ?: throw IOException()
        return getSetDir(token)?.findFile(DIRECTORY_FULL_BACKUP)
    }

    @Throws(IOException::class)
    fun getInputStream(file: DocumentFile): InputStream {
        return contentResolver.openInputStream(file.uri) ?: throw IOException()
    }

    @Throws(IOException::class)
    fun getOutputStream(file: DocumentFile): OutputStream {
        return contentResolver.openOutputStream(file.uri) ?: throw IOException()
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
