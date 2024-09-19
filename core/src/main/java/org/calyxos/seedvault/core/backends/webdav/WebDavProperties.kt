/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import android.content.Context
import org.calyxos.seedvault.core.backends.BackendProperties

public data class WebDavProperties(
    override val config: WebDavConfig,
    override val name: String,
) : BackendProperties<WebDavConfig>() {
    override val isUsb: Boolean = false
    override val requiresNetwork: Boolean = true
    override fun isUnavailableUsb(context: Context): Boolean = false
}
