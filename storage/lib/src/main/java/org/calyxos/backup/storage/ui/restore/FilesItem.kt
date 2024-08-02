/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import org.calyxos.backup.storage.restore.RestorableFile

public sealed interface FilesItem {
    public val name: String
    public val dir: String
    public val level: Int
    public val selected: Boolean
    public val size: Long
    public val lastModified: Long?
}

public data class FileItem internal constructor(
    internal val file: RestorableFile,
    override val level: Int,
    override val selected: Boolean,
) : FilesItem {
    override val name: String get() = file.name
    override val dir: String get() = file.dir
    override val size: Long get() = file.size
    override val lastModified: Long? get() = file.lastModified
}

public data class FolderItem(
    override val dir: String,
    override val name: String,
    override val level: Int,
    val numFiles: Int,
    override val size: Long,
    override val lastModified: Long?,
    override val selected: Boolean,
    val partiallySelected: Boolean,
    val expanded: Boolean,
) : FilesItem {
    init {
        check(selected || !partiallySelected) {
            "$dir was not selected, but partially selected"
        }
    }
}
