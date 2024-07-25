/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

public data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
)
