/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.app.restore.kv

import android.content.pm.PackageInfo

class KVRestoreState(
    val version: Byte,
    val token: Long,
    val name: String,
    val packageInfo: PackageInfo,
    /**
     * Optional [PackageInfo] for single package restore, optimizes restore of @pm@
     */
    val autoRestorePackageInfo: PackageInfo?,
)
