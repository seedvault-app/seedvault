/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.plugin

import android.content.Context
import android.net.Uri
import org.calyxos.backup.storage.plugin.saf.SafStoragePlugin
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafConfig
import java.io.IOException
import java.io.OutputStream

class TestSafStoragePlugin(
    private val appContext: Context,
    private val getLocationUri: () -> Uri?,
) : SafStoragePlugin(appContext) {

    private val safConfig
        get() = SafConfig(
            config = getLocationUri() ?: error("no uri"),
            name = "foo",
            isUsb = false,
            requiresNetwork = false,
            rootId = "bar",
        )
    override val delegate: SafBackend get() = SafBackend(appContext, safConfig)

    private val nullStream = object : OutputStream() {
        override fun write(b: Int) {
            // oops
        }
    }

    @Throws(IOException::class)
    override suspend fun getChunkOutputStream(chunkId: String): OutputStream {
        if (getLocationUri() == null) return nullStream
        return super.getChunkOutputStream(chunkId)
    }

    override suspend fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        return super.getBackupSnapshotOutputStream(timestamp)
    }

}
