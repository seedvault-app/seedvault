/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED

internal data class InstallResult(
    @get:VisibleForTesting
    val installResults: Map<String, ApkInstallResult> = mapOf(),
    /**
     * Is true, if the installation is finished, either because all packages were processed
     * or because an unexpected error happened along the way.
     * Is false, if the installation is still ongoing.
     */
    val isFinished: Boolean = false,
) {
    /**
     * The number of packages already processed.
     */
    val progress: Int = installResults.count {
        val state = it.value.state
        state != QUEUED && state != IN_PROGRESS
    }

    /**
     * The total number of packages to be considered for re-install.
     */
    val total: Int = installResults.size

    /**
     * A list of all [ApkInstallResult]s that are not in state [QUEUED].
     */
    val list: List<ApkInstallResult> = installResults.filterValues { result ->
        result.state != QUEUED
    }.values.run {
        if (isFinished) sortedWith(FailedFirstComparator()) else this
    }.toList()

    /**
     * Is true, if there is no packages to install and false otherwise.
     */
    val hasNoAppsToInstall: Boolean = installResults.isEmpty() && isFinished

    /**
     * Is true when one or more packages failed to install.
     */
    val hasFailed: Boolean = installResults.any { it.value.state == FAILED }

    fun update(
        packageName: String,
        updateFun: (ApkInstallResult) -> ApkInstallResult,
    ): InstallResult {
        val results = installResults.toMutableMap()
        val result = results[packageName]
        check(result != null) { "ApkRestoreResult for $packageName does not exist." }
        results[packageName] = updateFun(result)
        return copy(installResults = results)
    }

    fun fail(packageName: String, state: ApkInstallState = FAILED): InstallResult {
        return update(packageName) { it.copy(state = state) }
    }
}

data class ApkInstallResult(
    val packageName: String,
    val state: ApkInstallState,
    val metadata: PackageMetadata,
    val name: String? = metadata.name?.toString(),
    val icon: Drawable? = null,
) {
    val installerPackageName: CharSequence? get() = metadata.installer
}

internal class FailedFirstComparator : Comparator<ApkInstallResult> {
    override fun compare(a1: ApkInstallResult, a2: ApkInstallResult): Int {
        return if (a1.state == FAILED && a2.state != FAILED) -1
        else if (a2.state == FAILED && a1.state != FAILED) 1
        else {
            val str = a1.name ?: a1.packageName
            val otherStr = a2.name ?: a2.packageName
            str.compareTo(otherStr, true)
        }
    }
}

enum class ApkInstallState {
    QUEUED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,

    /**
     * The app was a system app and can't be installed on the restore device,
     * because it is not preset there.
     */
    FAILED_SYSTEM_APP
}
