/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.storage

import android.util.Log
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.ResourceType
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.plugins.chunkFolderRegex
import com.stevesoltys.seedvault.plugins.webdav.DIRECTORY_ROOT
import com.stevesoltys.seedvault.plugins.webdav.WebDavConfig
import com.stevesoltys.seedvault.plugins.webdav.WebDavStorage
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.plugin.PluginConstants.SNAPSHOT_EXT
import org.calyxos.backup.storage.plugin.PluginConstants.chunkRegex
import org.calyxos.backup.storage.plugin.PluginConstants.snapshotRegex
import org.koin.core.time.measureDuration
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class WebDavStoragePlugin(
    private val keyManager: KeyManager,
    /**
     * The result of Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
     */
    androidId: String,
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : WebDavStorage(webDavConfig, root), StoragePlugin {

    /**
     * The folder name is our user ID plus .sv extension (for SeedVault).
     * The user or `androidId` is unique to each combination of app-signing key, user, and device
     * so we don't leak anything by not hashing this and can use it as is.
     */
    private val folder: String = "$androidId.sv"

    @Throws(IOException::class)
    override suspend fun init() {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            davCollection.head { response ->
                debugLog { "Root exists: $response" }
            }
        } catch (e: NotFoundException) {
            val response = davCollection.createFolder()
            debugLog { "init() = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getAvailableChunkIds(): List<String> {
        val location = "$url/$folder/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        debugLog { "getAvailableChunkIds($location)" }

        val expectedChunkFolders = (0x00..0xff).map {
            Integer.toHexString(it).padStart(2, '0')
        }.toHashSet()
        val chunkIds = ArrayList<String>()
        try {
            val duration = measureDuration {
                davCollection.propfindDepthTwo { response, relation ->
                    debugLog { "getAvailableChunkIds() = $response" }
                    // This callback will be called for every file in the folder
                    if (relation != SELF && response.isFolder()) {
                        val name = response.hrefName()
                        if (chunkFolderRegex.matches(name)) {
                            expectedChunkFolders.remove(name)
                        }
                    } else if (relation != SELF && response.href.pathSize >= 2) {
                        val folderName =
                            response.href.pathSegments[response.href.pathSegments.size - 2]
                        if (folderName != folder && chunkFolderRegex.matches(folderName)) {
                            val name = response.hrefName()
                            if (chunkRegex.matches(name)) chunkIds.add(name)
                        }
                    }
                }
            }
            Log.i(TAG, "Retrieving chunks took $duration")
        } catch (e: NotFoundException) {
            debugLog { "Folder not found: $location" }
            davCollection.createFolder()
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error populating chunk folders: ", e)
        }
        Log.i(TAG, "Got ${chunkIds.size} available chunks")
        createMissingChunkFolders(expectedChunkFolders)
        return chunkIds
    }

    @Throws(IOException::class)
    private suspend fun createMissingChunkFolders(
        missingChunkFolders: Set<String>,
    ) {
        val s = missingChunkFolders.size
        for ((i, chunkFolderName) in missingChunkFolders.withIndex()) {
            val location = "$url/$folder/$chunkFolderName/".toHttpUrl()
            val davCollection = DavCollection(okHttpClient, location)
            val response = davCollection.createFolder()
            debugLog { "Created missing folder $chunkFolderName (${i + 1}/$s) $response" }
        }
    }

    override fun getMasterKey(): SecretKey = keyManager.getMainKey()
    override fun hasMasterKey(): Boolean = keyManager.hasMainKey()

    @Throws(IOException::class)
    override suspend fun getChunkOutputStream(chunkId: String): OutputStream {
        val chunkFolderName = chunkId.substring(0, 2)
        val location = "$url/$folder/$chunkFolderName/$chunkId".toHttpUrl()
        debugLog { "getChunkOutputStream($location) for $chunkId" }
        return try {
            getOutputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting OutputStream for $chunkId: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        val location = "$url/$folder/$timestamp$SNAPSHOT_EXT".toHttpUrl()
        debugLog { "getBackupSnapshotOutputStream($location)" }
        return try {
            getOutputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting OutputStream for $timestamp$SNAPSHOT_EXT: ", e)
        }
    }

    /************************* Restore *******************************/

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotsForRestore(): List<StoredSnapshot> {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        debugLog { "getBackupSnapshotsForRestore($location)" }

        val snapshots = ArrayList<StoredSnapshot>()
        try {
            davCollection.propfindDepthTwo { response, relation ->
                debugLog { "getBackupSnapshotsForRestore() = $response" }
                // This callback will be called for every file in the folder
                if (relation != SELF && !response.isFolder() && response.href.pathSize >= 2) {
                    val name = response.hrefName()
                    val match = snapshotRegex.matchEntire(name)
                    if (match != null) {
                        val timestamp = match.groupValues[1].toLong()
                        val folderName =
                            response.href.pathSegments[response.href.pathSegments.size - 2]
                        val storedSnapshot = StoredSnapshot(folderName, timestamp)
                        snapshots.add(storedSnapshot)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting snapshots for restore: ", e)
        }
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotInputStream(storedSnapshot: StoredSnapshot): InputStream {
        val timestamp = storedSnapshot.timestamp
        val location = "$url/${storedSnapshot.userId}/$timestamp$SNAPSHOT_EXT".toHttpUrl()
        debugLog { "getBackupSnapshotInputStream($location)" }
        return try {
            getInputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting InputStream for $storedSnapshot: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getChunkInputStream(
        snapshot: StoredSnapshot,
        chunkId: String,
    ): InputStream {
        val chunkFolderName = chunkId.substring(0, 2)
        val location = "$url/${snapshot.userId}/$chunkFolderName/$chunkId".toHttpUrl()
        debugLog { "getChunkInputStream($location) for $chunkId" }
        return try {
            getInputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting InputStream for $chunkFolderName/$chunkId: ", e)
        }
    }

    /************************* Pruning *******************************/

    @Throws(IOException::class)
    override suspend fun getCurrentBackupSnapshots(): List<StoredSnapshot> {
        val location = "$url/$folder/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        debugLog { "getCurrentBackupSnapshots($location)" }

        val snapshots = ArrayList<StoredSnapshot>()
        try {
            val duration = measureDuration {
                davCollection.propfind(
                    depth = 1,
                    reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME),
                ) { response, relation ->
                    debugLog { "getCurrentBackupSnapshots() = $response" }
                    // This callback will be called for every file in the folder
                    if (relation != SELF && !response.isFolder()) {
                        val match = snapshotRegex.matchEntire(response.hrefName())
                        if (match != null) {
                            val timestamp = match.groupValues[1].toLong()
                            val storedSnapshot = StoredSnapshot(folder, timestamp)
                            snapshots.add(storedSnapshot)
                        }
                    }
                }
            }
            Log.i(TAG, "getCurrentBackupSnapshots took $duration")
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting current snapshots: ", e)
        }
        Log.i(TAG, "Got ${snapshots.size} snapshots.")
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun deleteBackupSnapshot(storedSnapshot: StoredSnapshot) {
        val timestamp = storedSnapshot.timestamp
        Log.d(TAG, "Deleting snapshot $timestamp")

        val location = "$url/${storedSnapshot.userId}/$timestamp$SNAPSHOT_EXT".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            val response = suspendCoroutine { cont ->
                davCollection.delete { response ->
                    cont.resume(response)
                }
            }
            debugLog { "deleteBackupSnapshot() = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun deleteChunks(chunkIds: List<String>) {
        chunkIds.forEach { chunkId ->
            val chunkFolderName = chunkId.substring(0, 2)
            val location = "$url/$folder/$chunkFolderName/$chunkId".toHttpUrl()
            val davCollection = DavCollection(okHttpClient, location)

            try {
                val response = suspendCoroutine { cont ->
                    davCollection.delete { response ->
                        cont.resume(response)
                    }
                }
                debugLog { "deleteChunks($chunkId) = $response" }
            } catch (e: Exception) {
                if (e is IOException) throw e
                else throw IOException(e)
            }
        }
    }
}
