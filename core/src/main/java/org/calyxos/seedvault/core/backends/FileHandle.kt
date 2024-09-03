/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import org.calyxos.seedvault.core.backends.Constants.APP_SNAPSHOT_EXT
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_ICONS
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_METADATA
import org.calyxos.seedvault.core.backends.Constants.FILE_SNAPSHOT_EXT

public sealed class FileHandle {
    public abstract val name: String

    /**
     * The relative path relative to the storage root without prepended or trailing slash (/).
     */
    public abstract val relativePath: String
}

public data class TopLevelFolder(override val name: String) : FileHandle() {
    override val relativePath: String = name

    public companion object {
        public fun fromAndroidId(androidId: String): TopLevelFolder {
            return TopLevelFolder("$androidId.sv")
        }
    }
}

public sealed class LegacyAppBackupFile : FileHandle() {
    public abstract val token: Long
    public val topLevelFolder: TopLevelFolder get() = TopLevelFolder(token.toString())
    override val relativePath: String get() = "$token/$name"

    public data class Metadata(override val token: Long) : LegacyAppBackupFile() {
        override val name: String = FILE_BACKUP_METADATA
    }

    public data class IconsFile(override val token: Long) : LegacyAppBackupFile() {
        override val name: String = FILE_BACKUP_ICONS
    }

    public data class Blob(
        override val token: Long,
        override val name: String,
    ) : LegacyAppBackupFile()
}

public sealed class FileBackupFileType : FileHandle() {
    public abstract val androidId: String

    /**
     * The folder name is our user ID plus .sv extension (for SeedVault).
     * The user or `androidId` is unique to each combination of app-signing key, user, and device
     * so we don't leak anything by not hashing this and can use it as is.
     */
    public val topLevelFolder: TopLevelFolder get() = TopLevelFolder("$androidId.sv")

    public data class Blob(
        override val androidId: String,
        override val name: String,
    ) : FileBackupFileType() {
        override val relativePath: String get() = "$androidId.sv/${name.substring(0, 2)}/$name"
    }

    public data class Snapshot(
        override val androidId: String,
        val time: Long,
    ) : FileBackupFileType() {
        override val name: String = "$time$FILE_SNAPSHOT_EXT"
        override val relativePath: String get() = "$androidId.sv/$name"
    }
}

public sealed class AppBackupFileType : FileHandle() {
    public abstract val repoId: String

    public val topLevelFolder: TopLevelFolder get() = TopLevelFolder(repoId)

    public data class Blob(
        override val repoId: String,
        override val name: String,
    ) : AppBackupFileType() {
        override val relativePath: String get() = "$repoId/${name.substring(0, 2)}/$name"
    }

    public data class Snapshot(
        override val repoId: String,
        val hash: String,
    ) : AppBackupFileType() {
        override val name: String = "$hash$APP_SNAPSHOT_EXT"
        override val relativePath: String get() = "$repoId/$name"
    }
}

public data class FileInfo(
    val fileHandle: FileHandle,
    val size: Long,
)
