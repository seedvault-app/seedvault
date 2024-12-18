/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import java.io.OutputStream

/**
 * Used to save data with [Backend]s.
 */
public interface BackendSaver {
    /**
     * The number of bytes that will be saved or `null` if unknown.
     */
    public val size: Long

    /**
     * The SHA256 hash (in lower-case hex string representation) the bytes to be saved have,
     * or `null` if it isn't known.
     */
    public val sha256: String?

    /**
     * Called by the backend when it wants to save the data to the provided [outputStream].
     * Can be called more than once, in case the backend encountered an error saving.
     *
     * @return the number of bytes saved. Should be equal to [size].
     */
    public fun save(outputStream: OutputStream): Long
}
