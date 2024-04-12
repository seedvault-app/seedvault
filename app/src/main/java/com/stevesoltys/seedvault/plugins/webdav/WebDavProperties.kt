/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.content.Context
import com.stevesoltys.seedvault.plugins.StorageProperties

data class WebDavProperties(
    override val config: WebDavConfig,
    override val name: String,
) : StorageProperties<WebDavConfig>() {
    override val isUsb: Boolean = false
    override val requiresNetwork: Boolean = true
    override fun isUnavailableUsb(context: Context): Boolean = false
}
