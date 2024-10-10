/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import androidx.annotation.VisibleForTesting
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

public interface Backend {

    /**
     * Returns true if the plugin is working, or false if it isn't.
     * @throws Exception any kind of exception to provide more info on the error
     */
    public suspend fun test(): Boolean

    /**
     * Retrieves the available storage space in bytes.
     * @return the number of bytes available or null if the number is unknown.
     * Returning a negative number or zero to indicate unknown is discouraged.
     */
    public suspend fun getFreeSpace(): Long?

    public suspend fun save(handle: FileHandle): OutputStream

    public suspend fun load(handle: FileHandle): InputStream

    public suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit,
    )

    public suspend fun remove(handle: FileHandle)

    public suspend fun rename(from: TopLevelFolder, to: TopLevelFolder)

    @VisibleForTesting
    public suspend fun removeAll()

    /**
     * Returns the package name of the app that provides the storage backend
     * which is used for the current backup location.
     *
     * Backends are advised to cache this as it will be requested frequently.
     *
     * @return null if no package name could be found
     */
    public val providerPackageName: String?

    public suspend fun getAvailableBackupFileHandles(): List<FileHandle> {
        // v1 get all restore set tokens in root folder that have a metadata file
        // v2 get all snapshots in all repository folders
        return ArrayList<FileHandle>().apply {
            list(
                null,
                AppBackupFileType.Snapshot::class,
                LegacyAppBackupFile.Metadata::class,
            ) { fileInfo ->
                add(fileInfo.fileHandle)
            }
        }
    }
}
