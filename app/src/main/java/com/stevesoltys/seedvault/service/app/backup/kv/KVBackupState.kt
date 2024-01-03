/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.app.backup.kv

import android.content.pm.PackageInfo

class KVBackupState(
    internal val packageInfo: PackageInfo,
    val token: Long,
    val name: String,
    val db: KVDb,
) {
    var needsUpload: Boolean = false
}
