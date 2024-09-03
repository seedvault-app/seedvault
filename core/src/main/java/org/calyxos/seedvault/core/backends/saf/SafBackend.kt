/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
import android.provider.DocumentsContract.Root.COLUMN_ROOT_ID
import android.provider.DocumentsContract.renameDocument
import androidx.core.database.getIntOrNull
import androidx.documentfile.provider.DocumentFile
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_METADATA
import org.calyxos.seedvault.core.backends.Constants.appSnapshotRegex
import org.calyxos.seedvault.core.backends.Constants.blobFolderRegex
import org.calyxos.seedvault.core.backends.Constants.blobRegex
import org.calyxos.seedvault.core.backends.Constants.chunkFolderRegex
import org.calyxos.seedvault.core.backends.Constants.chunkRegex
import org.calyxos.seedvault.core.backends.Constants.fileFolderRegex
import org.calyxos.seedvault.core.backends.Constants.fileSnapshotRegex
import org.calyxos.seedvault.core.backends.Constants.repoIdRegex
import org.calyxos.seedvault.core.backends.Constants.tokenRegex
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

internal const val AUTHORITY_STORAGE = "com.android.externalstorage.documents"
internal const val ROOT_ID_DEVICE = "primary"

private const val DEBUG_LOG = true

public class SafBackend(
    private val context: Context,
    private val safProperties: SafProperties,
    root: String = DIRECTORY_ROOT,
) : Backend {

    private val log = KotlinLogging.logger {}

    private val cache = DocumentFileCache(context, safProperties.getDocumentFile(context), root)

    override suspend fun test(): Boolean {
        log.debugLog { "test()" }
        return cache.getRootFile().isDirectory
    }

    override suspend fun getFreeSpace(): Long? {
        log.debugLog { "getFreeSpace()" }
        val rootId = safProperties.rootId ?: return null
        val authority = safProperties.uri.authority
        // using DocumentsContract#buildRootUri(String, String) with rootId directly doesn't work
        val rootUri = DocumentsContract.buildRootsUri(authority)
        val projection = arrayOf(COLUMN_AVAILABLE_BYTES)
        // query directly for our rootId
        val bytesAvailable = context.contentResolver.query(
            rootUri, projection, "$COLUMN_ROOT_ID=?", arrayOf(rootId), null
        )?.use { c ->
            if (!c.moveToNext()) return@use null // no results
            val bytes = c.getIntOrNull(c.getColumnIndex(COLUMN_AVAILABLE_BYTES))
            if (bytes != null && bytes >= 0) return@use bytes.toLong()
            else return@use null
        }
        // if we didn't get anything from SAF, try some known hacks
        return if (bytesAvailable == null && authority == AUTHORITY_STORAGE) {
            if (rootId == ROOT_ID_DEVICE) {
                StatFs(Environment.getDataDirectory().absolutePath).availableBytes
            } else if (safProperties.isUsb) {
                val documentId = safProperties.uri.lastPathSegment ?: return null
                StatFs("/mnt/media_rw/${documentId.trimEnd(':')}").availableBytes
            } else null
        } else bytesAvailable
    }

    override suspend fun save(handle: FileHandle): OutputStream {
        log.debugLog { "save($handle)" }
        val file = cache.getOrCreateFile(handle)
        return file.getOutputStream(context.contentResolver)
    }

    override suspend fun load(handle: FileHandle): InputStream {
        log.debugLog { "load($handle)" }
        val file = cache.getOrCreateFile(handle)
        return file.getInputStream(context.contentResolver)
    }

    override suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit,
    ) {
        if (TopLevelFolder::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.IconsFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.Blob::class in fileTypes) throw UnsupportedOperationException()

        log.debugLog { "list($topLevelFolder, ${fileTypes.map { it.simpleName }})" }

        val folder = if (topLevelFolder == null) {
            cache.getRootFile()
        } else {
            cache.getOrCreateFile(topLevelFolder)
        }
        // limit depth based on wanted types and if top-level folder is given
        var depth = if (FileBackupFileType.Blob::class in fileTypes ||
            AppBackupFileType.Blob::class in fileTypes
        ) 3 else 2
        if (topLevelFolder != null) depth -= 1

        folder.listFilesRecursive(depth) { file ->
            if (!file.isFile) return@listFilesRecursive
            val parentName = file.parentFile?.name ?: return@listFilesRecursive
            val name = file.name ?: return@listFilesRecursive
            if (AppBackupFileType.Snapshot::class in fileTypes ||
                AppBackupFileType::class in fileTypes
            ) {
                val match = appSnapshotRegex.matchEntire(name)
                if (match != null && repoIdRegex.matches(parentName)) {
                    val snapshot = AppBackupFileType.Snapshot(
                        repoId = parentName,
                        hash = match.groupValues[1],
                    )
                    callback(FileInfo(snapshot, file.length()))
                }
            }
            if ((AppBackupFileType.Blob::class in fileTypes ||
                    AppBackupFileType::class in fileTypes)
            ) {
                val repoId = file.parentFile?.parentFile?.name ?: ""
                if (repoIdRegex.matches(repoId) && blobFolderRegex.matches(parentName)) {
                    if (blobRegex.matches(name)) {
                        val blob = AppBackupFileType.Blob(
                            repoId = repoId,
                            name = name,
                        )
                        callback(FileInfo(blob, file.length()))
                    }
                }
            }
            if (FileBackupFileType.Snapshot::class in fileTypes ||
                FileBackupFileType::class in fileTypes
            ) {
                val match = fileSnapshotRegex.matchEntire(name)
                if (match != null) {
                    val snapshot = FileBackupFileType.Snapshot(
                        androidId = parentName.substringBefore('.'),
                        time = match.groupValues[1].toLong(),
                    )
                    callback(FileInfo(snapshot, file.length()))
                }
            }
            if ((FileBackupFileType.Blob::class in fileTypes ||
                    FileBackupFileType::class in fileTypes)
            ) {
                val androidIdSv = file.parentFile?.parentFile?.name ?: ""
                if (fileFolderRegex.matches(androidIdSv) && chunkFolderRegex.matches(parentName)) {
                    if (chunkRegex.matches(name)) {
                        val blob = FileBackupFileType.Blob(
                            androidId = androidIdSv.substringBefore('.'),
                            name = name,
                        )
                        callback(FileInfo(blob, file.length()))
                    }
                }
            }
            if (LegacyAppBackupFile.Metadata::class in fileTypes && name == FILE_BACKUP_METADATA &&
                parentName.matches(tokenRegex)
            ) {
                val metadata = LegacyAppBackupFile.Metadata(parentName.toLong())
                callback(FileInfo(metadata, file.length()))
            }
        }
    }

    private suspend fun DocumentFile.listFilesRecursive(
        depth: Int,
        callback: (DocumentFile) -> Unit,
    ) {
        if (depth <= 0) return
        listFilesBlocking(context).forEach { file ->
            callback(file)
            if (file.isDirectory) file.listFilesRecursive(depth - 1, callback)
        }
    }

    override suspend fun remove(handle: FileHandle) {
        log.debugLog { "remove($handle)" }
        cache.getFile(handle)?.let { file ->
            if (!file.delete()) throw IOException("could not delete ${handle.relativePath}")
            cache.removeFromCache(handle)
        }
    }

    override suspend fun rename(from: TopLevelFolder, to: TopLevelFolder) {
        val toName = to.name // querying name is expensive
        log.debugLog { "rename($from, $toName)" }
        val fromFile = cache.getOrCreateFile(from)
        // don't use fromFile.renameTo(to.name) as that creates "${to.name} (1)"
        val newUri = renameDocument(context.contentResolver, fromFile.uri, toName)
            ?: throw IOException("could not rename ${from.relativePath}")
        cache.removeFromCache(from) // after renaming cached file isn't valid anymore
        val toFile = DocumentFile.fromTreeUri(context, newUri)
            ?: throw IOException("renamed URI invalid: $newUri")
        if (toFile.name != toName) {
            toFile.delete()
            throw IOException("renamed to ${toFile.name}, but expected $toName")
        }
    }

    override suspend fun removeAll() {
        log.debugLog { "removeAll()" }
        try {
            cache.getRootFile().listFilesBlocking(context).forEach { file ->
                log.debugLog { "  remove ${file.uri}" }
                file.delete()
            }
        } finally {
            cache.clearAll()
        }
    }

    override val providerPackageName: String? by lazy {
        log.debugLog { "providerPackageName" }
        val authority = safProperties.uri.authority ?: return@lazy null
        val providerInfo = context.packageManager.resolveContentProvider(authority, 0)
            ?: return@lazy null
        log.debugLog { "  ${providerInfo.packageName}" }
        providerInfo.packageName
    }

}

private inline fun KLogger.debugLog(crossinline block: () -> String) {
    if (DEBUG_LOG) debug { block() }
}
