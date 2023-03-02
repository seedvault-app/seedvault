/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage

internal fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

internal fun String.toByteArrayFromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
