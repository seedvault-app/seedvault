/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.restore.RestorableFile

public class FileSelectionManager {

    private val allFolders = HashMap<String, FolderItem>()
    private val allFiles = HashMap<String, MutableList<FileItem>>()
    private var snapshot: BackupSnapshot? = null
    private var expandedFolder: String? = null

    private val mFiles = MutableStateFlow<List<FilesItem>>(emptyList())
    public val files: StateFlow<List<FilesItem>> = mFiles.asStateFlow()

    @UiThread
    public fun onSnapshotChosen(snapshot: BackupSnapshot) {
        // clear previous state if existing
        clearState()
        // store snapshot for later
        this.snapshot = snapshot

        // cache files from snapshot within [RestorableFile] (for easier processing)
        snapshot.mediaFilesList.forEach { mediaFile ->
            cacheFileItem(RestorableFile(mediaFile))
        }
        snapshot.documentFilesList.forEach { documentFile ->
            cacheFileItem(RestorableFile(documentFile))
        }
        // figure out indentation level and display names for folders
        val sortedFolders = allFiles.keys.sorted()
        val levels = calculateFolderIndentationLevels(sortedFolders)
        val list = mutableListOf<FilesItem>()
        sortedFolders.forEach { folder ->
            // get size and lastModified from files in that folder
            val fileItems = allFiles[folder] ?: error("$folder not in allFiles")
            val size = fileItems.sumOf { it.file.size }
            val lastModified = fileItems.maxOf { it.file.lastModified ?: -1 }

            val level = levels[folder] ?: error("No level for $folder")
            val folderItem = FolderItem(
                dir = folder,
                name = level.second,
                level = level.first,
                numFiles = fileItems.size,
                size = size,
                lastModified = if (lastModified == -1L) null else lastModified,
                selected = true,
                partiallySelected = false,
                expanded = false,
            )
            allFolders[folder] = folderItem
            list.add(folderItem)
            allFiles[folder] = fileItems.sortedBy { it.name }.map {
                it.copy(level = level.first + 1)
            }.toMutableList()
        }
        mFiles.value = list
    }

    @UiThread
    internal fun onExpandClicked(clickedFolderItem: FolderItem) {
        // un-expand previously expanded folder, if any
        expandedFolder?.let { folder ->
            allFolders[folder] = allFolders[folder]?.copy(expanded = false)
                ?: error("Expanded folder $folder not in allFolders")
        }

        // update clickedFolderItem's expanded state in cache
        val newFolderItem = clickedFolderItem.copy(expanded = !clickedFolderItem.expanded)
        allFolders[clickedFolderItem.dir] = newFolderItem
        if (newFolderItem.expanded) expandedFolder = clickedFolderItem.dir

        // re-build file tree for UI
        mFiles.value = rebuildListFromCache()
    }

    @UiThread
    internal fun onCheckedChanged(toggledFilesItem: FilesItem) {
        if (toggledFilesItem is FileItem) {
            onFileItemCheckedChanged(toggledFilesItem)
        } else if (toggledFilesItem is FolderItem) {
            onFolderItemCheckedChanged(toggledFilesItem)
        }
        // re-build list from cache, so selection state gets updated there
        mFiles.value = rebuildListFromCache()
    }

    @UiThread
    public fun getBackupSnapshotAndReset(): BackupSnapshot {
        val snapshot = this.snapshot ?: error("No snapshot stored")
        // clear previous media files from snapshot
        val snapshotBuilder = snapshot.toBuilder()
            .clearMediaFiles()
            .clearDocumentFiles()
        // add only selected files back to snapshot
        allFiles.values.forEach { fileList ->
            fileList.forEach { file ->
                if (file.selected && file.file.mediaFile != null) {
                    snapshotBuilder.addMediaFiles(file.file.mediaFile)
                } else if (file.selected && file.file.docFile != null) {
                    snapshotBuilder.addDocumentFiles(file.file.docFile)
                }
            }
        }
        // clear state to free up memory
        clearState()
        return snapshotBuilder.build()
    }

    private fun cacheFileItem(restorableFile: RestorableFile) {
        val fileItem = FileItem(restorableFile, 0, true)
        allFiles.getOrPut(restorableFile.dir) {
            mutableListOf()
        }.add(fileItem)
    }

    private fun calculateFolderIndentationLevels(
        sortedFolders: List<String>,
    ): Map<String, Pair<Int, String>> {
        val levels = mutableMapOf<String, Pair<Int, String>>()
        sortedFolders.forEach { folder ->
            val parts = folder.split('/')
            for (i in parts.size - 1 downTo 0) {
                val subPath = parts.subList(0, i).joinToString("/")
                if (subPath.isBlank()) continue
                val subPathLevel = levels[subPath]
                if (subPathLevel != null) {
                    val name = if (i >= parts.size - 1) {
                        parts[i]
                    } else {
                        parts.subList(i, parts.size).joinToString("/")
                    }
                    levels[folder] = Pair(subPathLevel.first + 1, name)
                    return@forEach
                }
            }
            levels[folder] = Pair(0, folder.ifEmpty { "/" })
        }
        return levels
    }

    @UiThread
    private fun rebuildListFromCache(): MutableList<FilesItem> {
        val list = mutableListOf<FilesItem>()
        allFolders.keys.sorted().forEach { folder ->
            val folderItem = allFolders[folder] ?: error("No item for $folder")
            list.add(folderItem)
            val fileItems = allFiles[folder] ?: error("$folder not in allFiles")
            if (folderItem.expanded) {
                list.addAll(fileItems)
            }
        }
        return list
    }

    @UiThread
    private fun onFileItemCheckedChanged(fileItem: FileItem) {
        // get all file items from this dir and update only the changed one
        val fileItems = allFiles[fileItem.dir]
            ?: error("no files for ${fileItem.dir}")
        fileItems.replaceAll {
            if (it.file == fileItem.file) it.copy(selected = !it.selected)
            else it
        }
        // figure out how to update parent folder
        var allSelected = true
        var noneSelected = true
        fileItems.forEach { item ->
            if (item.selected) noneSelected = false
            else allSelected = false
        }
        // update parent folder
        val folderItem = allFolders[fileItem.dir]
            ?: error("no folder for ${fileItem.dir}")
        allFolders[fileItem.dir] = folderItem.copy(
            selected = allSelected || !noneSelected,
            partiallySelected = !allSelected && !noneSelected,
        )
    }

    @UiThread
    private fun onFolderItemCheckedChanged(folderItem: FolderItem) {
        val newSelected = if (!folderItem.selected) {
            true // was not selected, so now it should be
        } else if (folderItem.partiallySelected) {
            true // was only partially selected, so now select all
        } else {
            false // was fully selected, so now deselect
        }
        allFiles[folderItem.dir]?.replaceAll {
            it.copy(selected = newSelected)
        }
        allFolders[folderItem.dir] = folderItem.copy(
            selected = newSelected,
            partiallySelected = false,
        )
    }

    private fun clearState() {
        snapshot = null
        expandedFolder = null
        allFolders.clear()
        allFiles.clear()
        mFiles.value = emptyList()
    }

}
