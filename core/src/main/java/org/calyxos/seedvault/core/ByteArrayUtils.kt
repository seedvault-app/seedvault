/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core

public fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

public fun String.toByteArrayFromHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
