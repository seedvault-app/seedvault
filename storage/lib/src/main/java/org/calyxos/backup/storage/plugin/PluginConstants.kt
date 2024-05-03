/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.plugin

public object PluginConstants {

    public const val SNAPSHOT_EXT: String = ".SeedSnap"
    public val folderRegex: Regex = Regex("^[a-f0-9]{16}\\.sv$")
    public val chunkFolderRegex: Regex = Regex("[a-f0-9]{2}")
    public val chunkRegex: Regex = Regex("[a-f0-9]{64}")
    public val snapshotRegex: Regex = Regex("([0-9]{13})\\.SeedSnap") // good until the year 2286
    public const val MIME_TYPE: String = "application/octet-stream"
    public const val CHUNK_FOLDER_COUNT: Int = 256

}
