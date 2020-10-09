package com.stevesoltys.seedvault.restore.install

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
import android.content.pm.PackageManager
import android.net.Uri

internal class InstallIntentCreator(
    private val packageManager: PackageManager
) {

    private val installerToPackage = mapOf(
        "org.fdroid.fdroid" to "org.fdroid.fdroid",
        "org.fdroid.fdroid.privileged" to "org.fdroid.fdroid",
        "com.aurora.store" to "com.aurora.store",
        "com.aurora.services" to "com.aurora.store",
        "com.android.vending" to "com.android.vending"
    )
    private val isPackageInstalled = HashMap<String, Boolean>()

    fun getIntent(packageName: CharSequence, installerPackageName: CharSequence?): Intent {
        val i = Intent(ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            addFlags(FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        getIntentPackage(installerPackageName)?.let { i.setPackage(it) }
        return i
    }

    /**
     * Returns a package name to restrict the intent to
     * or null if we can't restrict the intent.
     */
    private fun getIntentPackage(installerPackageName: CharSequence?): String? {
        if (installerPackageName == null) return null
        val packageName = installerToPackage[installerPackageName] ?: return null
        val isInstalled = isPackageInstalled.getOrPut(packageName) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        return if (isInstalled) packageName else null
    }

}
