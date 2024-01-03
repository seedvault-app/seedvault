/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.app.restore.full

import android.content.pm.PackageInfo
import java.io.InputStream

class FullRestoreState(
    val version: Byte,
    val token: Long,
    val name: String,
    val packageInfo: PackageInfo,
) {
    var inputStream: InputStream? = null
}
