package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.getSystemContext
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.settings.Storage
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private val TAG = DocumentsProviderStoragePlugin::class.java.simpleName

@Suppress("BlockingMethodInNonBlockingContext")
internal class DocumentsProviderStoragePlugin(
    private val appContext: Context,
    private val storage: DocumentsStorage,
) : StoragePlugin {

    /**
     * Attention: This context might be from a different user. Use with care.
     */
    private val context: Context
        get() = appContext.getSystemContext {
            storage.storage?.isUsb == true
        }

    private val packageManager: PackageManager = appContext.packageManager

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        // reset current storage
        storage.reset(token)

        // get or create root backup dir
        storage.rootBackupDir ?: throw IOException()
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // wipe existing data
        storage.getSetDir()?.deleteContents(context)

        // reset storage without new token, so folders get recreated
        // otherwise stale DocumentFiles will hang around
        storage.reset(null)

        // create backup folders
        storage.currentSetDir ?: throw IOException()
    }

    @Throws(IOException::class)
    override suspend fun hasData(token: Long, name: String): Boolean {
        val setDir = storage.getSetDir(token) ?: return false
        return setDir.findFileBlocking(context, name) != null
    }

    @Throws(IOException::class)
    override suspend fun getOutputStream(token: Long, name: String): OutputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.createOrGetFile(context, name)
        return storage.getOutputStream(file)
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, name) ?: throw FileNotFoundException()
        return storage.getInputStream(file)
    }

    @Throws(IOException::class)
    override suspend fun removeData(token: Long, name: String) {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, name) ?: return
        if (!file.delete()) throw IOException("Failed to delete $name")
    }

    @Throws(IOException::class)
    override suspend fun hasBackup(storage: Storage): Boolean {
        // potentially get system user context if needed here
        val c = appContext.getSystemContext { storage.isUsb }
        val parent = DocumentFile.fromTreeUri(c, storage.uri) ?: throw AssertionError()
        val rootDir = parent.findFileBlocking(c, DIRECTORY_ROOT) ?: return false
        val backupSets = getBackups(c, rootDir)
        return backupSets.isNotEmpty()
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        val rootDir = storage.rootBackupDir ?: return null
        val backupSets = getBackups(context, rootDir)
        val iterator = backupSets.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null // end sequence
            val backupSet = iterator.next()
            EncryptedMetadata(backupSet.token) {
                storage.getInputStream(backupSet.metadataFile)
            }
        }
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.getAuthority() ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}

class BackupSet(val token: Long, val metadataFile: DocumentFile)

@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun getBackups(context: Context, rootDir: DocumentFile): List<BackupSet> {
    val backupSets = ArrayList<BackupSet>()
    val files = try {
        // block until the DocumentsProvider has results
        rootDir.listFilesBlocking(context)
    } catch (e: IOException) {
        Log.e(TAG, "Error loading backups from storage", e)
        return backupSets
    }
    for (set in files) {
        // retrieve name only once as this causes a DB query
        val name = set.name

        // get current token from set or continue to next file/set
        val token = set.getTokenOrNull(name) ?: continue

        // block until children of set are available
        val metadata = try {
            set.findFileBlocking(context, FILE_BACKUP_METADATA)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading metadata file in backup set folder: $name", e)
            null
        }
        if (metadata == null) {
            Log.w(TAG, "Missing metadata file in backup set folder: $name")
        } else {
            backupSets.add(BackupSet(token, metadata))
        }
    }
    return backupSets
}

private val tokenRegex = Regex("([0-9]{13})") // good until the year 2286
private val chunkFolderRegex = Regex("[a-f0-9]{2}")

private fun DocumentFile.getTokenOrNull(name: String?): Long? {
    val looksLikeToken = name != null && tokenRegex.matches(name)
    // check for isDirectory only if we already have a valid token (causes DB query)
    if (!looksLikeToken || !isDirectory) {
        // only log unexpected output
        if (name != null && isUnexpectedFile(name)) {
            Log.w(TAG, "Found invalid backup set folder: $name")
        }
        return null
    }
    return try {
        name?.toLong()
    } catch (e: NumberFormatException) {
        throw AssertionError(e)
    }
}

private fun isUnexpectedFile(name: String): Boolean {
    return name != FILE_NO_MEDIA &&
        !chunkFolderRegex.matches(name) &&
        !name.endsWith(".SeedSnap")
}
