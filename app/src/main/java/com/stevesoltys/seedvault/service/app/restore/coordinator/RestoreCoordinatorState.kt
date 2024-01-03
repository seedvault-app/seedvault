/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.app.restore.coordinator

import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.service.metadata.BackupMetadata

data class RestoreCoordinatorState(
    val token: Long,
    val packages: Iterator<PackageInfo>,
    /**
     * Optional [PackageInfo] for single package restore, to reduce data needed to read for @pm@
     */
    val autoRestorePackageInfo: PackageInfo?,
    val backupMetadata: BackupMetadata,
) {
    var currentPackage: String? = null
}
