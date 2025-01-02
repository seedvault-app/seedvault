/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import android.content.Context
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.calyxos.seedvault.core.backends.webdav.WebDavBackend
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig

public class BackendFactory {
    public fun createSafBackend(context: Context, config: SafProperties): Backend =
        RetryBackend(SafBackend(context, config))

    public fun createWebDavBackend(config: WebDavConfig): Backend =
        RetryBackend(WebDavBackend(config))
}
