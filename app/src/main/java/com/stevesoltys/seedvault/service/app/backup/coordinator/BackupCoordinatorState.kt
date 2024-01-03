/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.app.backup.coordinator

import com.stevesoltys.seedvault.service.metadata.PackageState

class BackupCoordinatorState(
    var calledInitialize: Boolean,
    var calledClearBackupData: Boolean,
    var cancelReason: PackageState,
) {
    val expectFinish: Boolean
        get() = calledInitialize || calledClearBackupData

    fun onFinish() {
        calledInitialize = false
        calledClearBackupData = false
        cancelReason = PackageState.UNKNOWN_ERROR
    }
}
