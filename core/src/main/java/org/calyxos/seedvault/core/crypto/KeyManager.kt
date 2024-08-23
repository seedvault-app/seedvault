/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.crypto

import java.security.KeyStore
import javax.crypto.SecretKey

public interface KeyManager {
    /**
     * Returns the main key, so it can be used for deriving sub-keys.
     *
     * Note that any attempt to export the key will return null or an empty [ByteArray],
     * because the key can not leave the [KeyStore]'s hardware security module.
     */
    public fun getMainKey(): SecretKey
}
