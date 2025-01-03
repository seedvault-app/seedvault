/*
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import androidx.annotation.VisibleForTesting
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.InputStream
import kotlin.reflect.KClass

@VisibleForTesting
internal const val MAX_RETRIES = 5

internal class RetryBackend(private val delegate: Backend) : Backend {

    private val log = KotlinLogging.logger { }

    override val id: BackendId = delegate.id

    override suspend fun test(): Boolean = retry {
        delegate.test()
    }

    override suspend fun getFreeSpace(): Long? = retry {
        delegate.getFreeSpace()
    }

    override suspend fun save(handle: FileHandle, saver: BackendSaver): Long = retry {
        // TODO need to delete failed file?
        delegate.save(handle, saver)
    }

    override suspend fun load(handle: FileHandle): InputStream = retry {
        delegate.load(handle)
    }

    override suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit
    ) = retry {
        delegate.list(topLevelFolder, *fileTypes, callback = callback)
    }

    override suspend fun remove(handle: FileHandle) = retry {
        delegate.remove(handle)
    }

    override suspend fun rename(from: TopLevelFolder, to: TopLevelFolder) = retry {
        delegate.rename(from, to)
    }

    @VisibleForTesting
    override suspend fun removeAll() = retry {
        delegate.removeAll()
    }

    override fun isTransientException(e: Exception): Boolean = delegate.isTransientException(e)

    private suspend fun <T> retry(block: suspend () -> T): T {
        return retry(0, 1000, block)
    }

    private suspend fun <T> retry(retries: Int, delayMs: Long, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            val newRetries = retries + 1
            val newDelayMs = delayMs * 2
            if (newRetries < MAX_RETRIES && isTransientException(e)) {
                log.warn(e) { "Retrying #$newRetries after error and delay ${newDelayMs}ms: " }
                delay(newDelayMs)
                return retry(newRetries, newDelayMs, block)
            } else {
                throw e
            }
        }
    }

    override val providerPackageName: String? get() = delegate.providerPackageName
}
