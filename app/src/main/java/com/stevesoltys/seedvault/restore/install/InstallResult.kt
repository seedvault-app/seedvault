package com.stevesoltys.seedvault.restore.install

import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

internal typealias InstallResult = Map<String, ApkInstallResult>

internal fun InstallResult.getInProgress(): ApkInstallResult? {
    val filtered = filterValues { result -> result.state == ApkInstallState.IN_PROGRESS }
    if (filtered.isEmpty()) return null
    check(filtered.size == 1) { "More than one package in progress: ${filtered.keys}" }
    return filtered.values.first()
}

internal class MutableInstallResult(initialCapacity: Int) :
    ConcurrentHashMap<String, ApkInstallResult>(initialCapacity) {
    fun update(
        packageName: String,
        updateFun: (ApkInstallResult) -> ApkInstallResult
    ): MutableInstallResult {
        val result = get(packageName)
        check(result != null) { "ApkRestoreResult for $packageName does not exist." }
        set(packageName, updateFun(result))
        return this
    }
}

internal data class ApkInstallResult(
    val packageName: CharSequence,
    val progress: Int,
    val total: Int,
    val state: ApkInstallState,
    val name: CharSequence? = null,
    val icon: Drawable? = null
) : Comparable<ApkInstallResult> {
    override fun compareTo(other: ApkInstallResult): Int {
        return other.progress.compareTo(progress)
    }
}

internal enum class ApkInstallState {
    QUEUED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}
