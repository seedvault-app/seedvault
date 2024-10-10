/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.github.luben.zstd.ZstdOutputStream
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.proto.Snapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Constants.appSnapshotRegex
import org.calyxos.seedvault.core.toHexString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

internal const val FOLDER_SNAPSHOTS = "snapshots"

/**
 * Manages interactions with snapshots, such as loading, saving and removing them.
 * Also keeps a reference to the [latestSnapshot] that holds important re-usable data.
 */
internal class SnapshotManager(
    private val snapshotFolderRoot: File,
    private val crypto: Crypto,
    private val loader: Loader,
    private val backendManager: BackendManager,
) {

    private val log = KotlinLogging.logger {}
    private val snapshotFolder: File get() = File(snapshotFolderRoot, crypto.repoId)

    /**
     * The latest [Snapshot]. May be stale if [onSnapshotsLoaded] has not returned
     * or wasn't called since new snapshots have been created.
     */
    @Volatile
    var latestSnapshot: Snapshot? = null
        private set

    /**
     * Call this before starting a backup run with the [handles] of snapshots
     * currently available on the backend.
     */
    suspend fun onSnapshotsLoaded(handles: List<AppBackupFileType.Snapshot>): List<Snapshot> {
        // first reset latest snapshot, otherwise we'd hang on to a stale one
        // e.g. when switching to new storage without any snapshots
        latestSnapshot = null
        return handles.mapNotNull { snapshotHandle ->
            val snapshot = try {
                loadSnapshot(snapshotHandle)
            } catch (e: Exception) {
                // This isn't ideal, but the show must go on and we take the snapshots we can get.
                // After the first load, a snapshot will get cached, so we are not hitting backend.
                // TODO use a re-trying backend for snapshot loading
                log.error(e) { "Error loading snapshot: $snapshotHandle" }
                return@mapNotNull null
            }
            // update latest snapshot if this one is more recent
            if (snapshot.token > (latestSnapshot?.token ?: 0)) latestSnapshot = snapshot
            snapshot
        }
    }

    /**
     * Saves the given [snapshot] to the backend and local cache.
     *
     * @throws IOException or others if saving fails.
     */
    @Throws(IOException::class)
    suspend fun saveSnapshot(snapshot: Snapshot) {
        // compress payload and get size
        val payloadStream = ByteArrayOutputStream()
        ZstdOutputStream(payloadStream).use { zstdOutputStream ->
            snapshot.writeTo(zstdOutputStream)
        }
        val payloadSize = payloadStream.size()
        val payloadSizeBytes = ByteBuffer.allocate(4).putInt(payloadSize).array()

        // encrypt compressed payload and assemble entire blob
        val byteStream = ByteArrayOutputStream()
        byteStream.write(VERSION.toInt())
        crypto.newEncryptingStream(byteStream, crypto.getAdForVersion()).use { cryptoStream ->
            cryptoStream.write(payloadSizeBytes)
            cryptoStream.write(payloadStream.toByteArray())
            // not adding any padding here, because it doesn't matter for snapshots
        }
        payloadStream.reset()
        val bytes = byteStream.toByteArray()
        byteStream.reset()

        // compute hash and save blob
        val sha256 = crypto.sha256(bytes).toHexString()
        val snapshotHandle = AppBackupFileType.Snapshot(crypto.repoId, sha256)
        backendManager.backend.save(snapshotHandle).use { outputStream ->
            outputStream.write(bytes)
        }
        // save to local cache while at it
        try {
            if (!snapshotFolder.isDirectory) snapshotFolder.mkdirs()
            File(snapshotFolder, snapshotHandle.name).outputStream().use { outputStream ->
                outputStream.write(bytes)
            }
        } catch (e: Exception) { // we'll let this one pass
            log.error(e) { "Error saving snapshot ${snapshotHandle.hash} to cache: " }
        }
    }

    /**
     * Removes the snapshot referenced by the given [snapshotHandle] from the backend
     * and local cache.
     */
    @Throws(IOException::class)
    suspend fun removeSnapshot(snapshotHandle: AppBackupFileType.Snapshot) {
        backendManager.backend.remove(snapshotHandle)
        // remove from cache as well
        File(snapshotFolder, snapshotHandle.name).delete()
    }

    /**
     * Loads and parses the snapshot referenced by the given [snapshotHandle].
     * If a locally cached version exists, the backend will not be hit.
     */
    @Throws(GeneralSecurityException::class, UnsupportedVersionException::class, IOException::class)
    suspend fun loadSnapshot(snapshotHandle: AppBackupFileType.Snapshot): Snapshot {
        val file = File(snapshotFolder, snapshotHandle.name)
        snapshotFolder.mkdirs()
        val inputStream = if (file.isFile) {
            try {
                loader.loadFile(file, snapshotHandle.hash)
            } catch (e: Exception) {
                log.error(e) { "Error loading $snapshotHandle from local cache. Trying backend..." }
                loader.loadFile(snapshotHandle, file)
            }
        } else {
            loader.loadFile(snapshotHandle, file)
        }
        return inputStream.use { Snapshot.parseFrom(it) }
    }

    @Throws(GeneralSecurityException::class, UnsupportedVersionException::class, IOException::class)
    fun loadCachedSnapshots(): List<Snapshot> {
        if (!snapshotFolder.isDirectory) return emptyList()
        return snapshotFolder.listFiles()?.mapNotNull { file ->
            val match = appSnapshotRegex.matchEntire(file.name)
            if (match == null) {
                log.error { "Unexpected file found: $file" }
                null
            } else {
                loader.loadFile(file, match.groupValues[1]).use { Snapshot.parseFrom(it) }
            }
        } ?: throw IOException("Could not access snapshotFolder")
    }

}
