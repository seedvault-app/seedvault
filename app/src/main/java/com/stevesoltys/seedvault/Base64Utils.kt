/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import java.nio.charset.Charset
import java.util.Base64

val Utf8: Charset = Charset.forName("UTF-8")

fun ByteArray.encodeBase64(): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}

fun String.encodeBase64(): String {
    return toByteArray(Utf8).encodeBase64()
}

fun String.decodeBase64(): String {
    return String(Base64.getUrlDecoder().decode(this))
}
