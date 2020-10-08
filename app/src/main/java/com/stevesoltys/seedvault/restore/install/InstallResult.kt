package com.stevesoltys.seedvault.restore.install

import android.graphics.drawable.Drawable
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import java.util.concurrent.ConcurrentHashMap

internal interface InstallResult {
    /**
     * Returns true, if there is no packages to install and false otherwise.
     */
    fun isEmpty(): Boolean

    /**
     * Returns the [ApkInstallResult] of the package currently [IN_PROGRESS]
     * or null if there is no such package.
     */
    fun getInProgress(): ApkInstallResult?

    /**
     * Get all [ApkInstallResult]s that are not in state [QUEUED].
     */
    fun getNotQueued(): Collection<ApkInstallResult>

    /**
     * Get the [ApkInstallResult] for the given package name or null if none exists.
     */
    operator fun get(packageName: String): ApkInstallResult?
}

internal class MutableInstallResult(initialCapacity: Int) : InstallResult {

    private val installResults = ConcurrentHashMap<String, ApkInstallResult>(initialCapacity)

    override fun isEmpty() = installResults.isEmpty()

    override fun getInProgress(): ApkInstallResult? {
        val filtered = installResults.filterValues { result -> result.state == IN_PROGRESS }
        if (filtered.isEmpty()) return null
        check(filtered.size == 1) { "More than one package in progress: ${filtered.keys}" }
        return filtered.values.first()
    }

    override fun getNotQueued(): Collection<ApkInstallResult> {
        return installResults.filterValues { result -> result.state != QUEUED }.values
    }

    override fun get(packageName: String) = installResults[packageName]

    operator fun set(packageName: String, installResult: ApkInstallResult) {
        installResults[packageName] = installResult
    }

    fun update(
        packageName: String,
        updateFun: (ApkInstallResult) -> ApkInstallResult
    ): MutableInstallResult {
        val result = get(packageName)
        check(result != null) { "ApkRestoreResult for $packageName does not exist." }
        installResults[packageName] = updateFun(result)
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
