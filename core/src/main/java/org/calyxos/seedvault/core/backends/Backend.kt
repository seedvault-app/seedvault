/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import okio.BufferedSink
import okio.BufferedSource
import kotlin.reflect.KClass

public interface Backend {

    public suspend fun save(handle: FileHandle): BufferedSink

    public suspend fun load(handle: FileHandle): BufferedSource

    public suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit,
    )

    public suspend fun remove(handle: FileHandle)

    public suspend fun rename(from: TopLevelFolder, to: TopLevelFolder)

    // TODO really all?
    public suspend fun removeAll()

}
