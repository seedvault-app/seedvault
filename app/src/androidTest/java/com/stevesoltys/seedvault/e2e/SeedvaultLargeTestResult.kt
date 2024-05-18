/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e

import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.restore.AppRestoreResult

/**
 * Contains maps of (package name -> SHA-256 hashes) of application data.
 *
 * During backups and restores, we intercept the package data and store the result here.
 * We can use this to validate that the restored app data actually matches the backed up data.
 *
 * For full backups, the mapping is: Map<PackageName, SHA-256>
 * For K/V backups, the mapping is: Map<PackageName, Map<Key, SHA-256>>
 */
internal data class SeedvaultLargeTestResult(
    val backupResults: Map<String, PackageMetadata?> = emptyMap(),
    val restoreResults: Map<String, AppRestoreResult?> = emptyMap(),
    val full: MutableMap<String, String>,
    val kv: MutableMap<String, MutableMap<String, String>>,
    val userApps: List<PackageInfo>,
    val userNotAllowedApps: List<PackageInfo>,
) {
    fun allUserApps() = userApps + userNotAllowedApps
}
