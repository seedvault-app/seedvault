/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

public object Constants {

    public const val DIRECTORY_ROOT: String = ".SeedVaultAndroidBackup"
    internal const val FILE_BACKUP_METADATA = ".backup.metadata"
    internal const val FILE_BACKUP_ICONS = ".backup.icons"
    public val tokenRegex: Regex = Regex("^([0-9]{13})$") // good until the year 2286
    public const val APP_SNAPSHOT_EXT: String = ".snapshot"
    public const val FILE_SNAPSHOT_EXT: String = ".SeedSnap"
    public val repoIdRegex: Regex = Regex("^[a-f0-9]{64}$")
    public val fileFolderRegex: Regex = Regex("^[a-f0-9]{16}\\.sv$")
    public val chunkFolderRegex: Regex = Regex("^[a-f0-9]{2}$")
    public val blobFolderRegex: Regex = chunkFolderRegex
    public val chunkRegex: Regex = repoIdRegex
    public val blobRegex: Regex = repoIdRegex
    // good until year 2286
    public val appSnapshotRegex: Regex = Regex("(^[a-f0-9]{64})\\.snapshot$")
    public val fileSnapshotRegex: Regex = Regex("(^[0-9]{13})\\.SeedSnap$") // good until year 2286
    public const val MIME_TYPE: String = "application/octet-stream"

}
