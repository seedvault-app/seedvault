/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.backup.BackupDocumentFile
import org.calyxos.backup.storage.backup.BackupMediaFile
import org.calyxos.backup.storage.backup.BackupSnapshot

internal data class RestorableChunk(
    val chunkId: String,
) {
    val files: ArrayList<RestorableFile> = ArrayList()
    val isSingle: Boolean get() = files.size == 1 && files[0].chunkIdsCount == 1

    fun add(file: BackupMediaFile): Boolean = files.add(RestorableFile(file))
    fun add(file: BackupDocumentFile): Boolean = files.add(RestorableFile(file))

    /**
     * Call this after the RestorableChunk is complete and **before** using it for restore.
     */
    fun finalize() {
        // entries in the zip chunk need to be sorted by their index in the zip
        files.sortBy { it.zipIndex }
        // There might be duplicates in case the *exact* same set of files exists more than once
        // so they'll produce the same chunk ID.
        // But since the content is there and this is an unlikely scenario, we drop the duplicates.
        var lastIndex = 0
        val iterator = files.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            val i = file.zipIndex
            when {
                i < lastIndex -> error("unsorted list")
                i == lastIndex -> iterator.remove() // remove duplicate
                i > lastIndex -> lastIndex = i // gaps are possible when we don't restore all files
            }
        }
    }
}

internal data class RestorableFile(
    val mediaFile: BackupMediaFile?,
    val docFile: BackupDocumentFile?,
) : BackupFile {
    constructor(file: BackupMediaFile) : this(file, null)
    constructor(file: BackupDocumentFile) : this(null, file)

    init {
        check((mediaFile == null) xor (docFile == null)) { "Only one file" }
    }

    val chunkIdsCount: Int get() = mediaFile?.chunkIdsCount ?: docFile!!.chunkIdsCount
    val chunkIds: List<String> get() = mediaFile?.chunkIdsList ?: docFile!!.chunkIdsList
    val zipIndex: Int get() = mediaFile?.zipIndex ?: docFile!!.zipIndex
    val dir: String get() = mediaFile?.path ?: docFile!!.path
    val name: String get() = mediaFile?.name ?: docFile!!.name
    override val path: String = "$dir/$name"
    override val size: Long get() = mediaFile?.size ?: docFile!!.size
    override val volume: String get() = mediaFile?.volume ?: docFile!!.volume
    override val lastModified: Long?
        get() {
            val mod = mediaFile?.lastModified ?: docFile!!.lastModified
            return if (mod == 0L) null else mod
        }
}

internal data class FileSplitterResult(
    /**
     * Zip chunks containing several small files.
     */
    val zipChunks: Collection<RestorableChunk>,
    /**
     * Chunks that contains exactly one single file.
     */
    val singleChunks: Collection<RestorableChunk>,
    /**
     * A map of chunk ID to chunks
     * where either the chunk is used by more than one file
     * or a file needs more than one chunk.
     */
    val multiChunkMap: Map<String, RestorableChunk>,
    /**
     * Files referenced in [multiChunkMap] sorted for restoring.
     */
    val multiChunkFiles: Collection<RestorableFile>,
)

/**
 * Splits files to be restored into several types that can be restored in parallel.
 */
internal object FileSplitter {

    fun splitSnapshot(snapshot: BackupSnapshot): FileSplitterResult {
        val zipChunkMap = HashMap<String, RestorableChunk>()
        val chunkMap = HashMap<String, RestorableChunk>()

        snapshot.mediaFilesList.forEach { mediaFile ->
            if (mediaFile.zipIndex > 0) {
                check(mediaFile.chunkIdsCount == 1) { "More than 1 zip chunk: $mediaFile" }
                val chunkId = mediaFile.chunkIdsList[0]
                val zipChunk = zipChunkMap.getOrPut(chunkId) { RestorableChunk(chunkId) }
                zipChunk.add(mediaFile)
            } else for (chunkId in mediaFile.chunkIdsList) {
                val chunk = chunkMap.getOrPut(chunkId) { RestorableChunk(chunkId) }
                chunk.add(mediaFile)
            }
        }
        snapshot.documentFilesList.forEach { docFile ->
            if (docFile.zipIndex > 0) {
                check(docFile.chunkIdsCount == 1) { "More than 1 zip chunk: $docFile" }
                val chunkId = docFile.chunkIdsList[0]
                val zipChunk = zipChunkMap.getOrPut(chunkId) { RestorableChunk(chunkId) }
                zipChunk.add(docFile)
            } else for (chunkId in docFile.chunkIdsList) {
                val chunk = chunkMap.getOrPut(chunkId) { RestorableChunk(chunkId) }
                chunk.add(docFile)
            }
        }
        // entries in the zip chunk need to be sorted by their index in the zip, duplicated removed
        zipChunkMap.values.forEach { zipChunk -> zipChunk.finalize() }
        val singleChunks = chunkMap.values.filter { it.isSingle }
        val multiChunks = chunkMap.filterValues { !it.isSingle }
        return FileSplitterResult(
            zipChunks = zipChunkMap.values,
            singleChunks = singleChunks,
            multiChunkMap = multiChunks,
            multiChunkFiles = getMultiFiles(multiChunks),
        )
    }

    private fun getMultiFiles(chunkMap: Map<String, RestorableChunk>): List<RestorableFile> {
        val files = HashSet<RestorableFile>()
        chunkMap.values.forEach { chunk ->
            chunk.files.forEach { file ->
                files.add(file)
            }
        }
        return files.sortedWith { f1, f2 ->
            when {
                f1.chunkIdsCount < f2.chunkIdsCount -> -1
                f1.chunkIdsCount == f2.chunkIdsCount -> {
                    f1.chunkIds.joinToString().compareTo(f2.chunkIds.joinToString())
                }
                else -> 1
            }
        }
    }

}
