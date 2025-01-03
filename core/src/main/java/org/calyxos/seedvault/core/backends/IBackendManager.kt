/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

public interface IBackendManager {
    public val backend: Backend
    public val isOnRemovableDrive: Boolean
    public val requiresNetwork: Boolean
}

public enum class BackendId {
    SAF,
    WEBDAV,
}
