/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import android.app.PendingIntent
import android.content.Context
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.ui.Notifications

public open class NotificationRestoreObserver internal constructor(private val n: Notifications) :
    RestoreObserver {

    public constructor(context: Context) : this(Notifications(context))

    private var totalFiles = 0
    private var filesRestored = 0
    private var filesRemovedAsDuplicates = 0
    private var filesWithError = 0

    override fun onRestoreStart(numFiles: Int, totalSize: Long) {
        totalFiles = numFiles
        n.updateRestoreNotification(filesRestored + filesWithError, totalFiles)
    }

    override fun onFileDuplicatesRemoved(num: Int) {
        filesRemovedAsDuplicates = num
    }

    override fun onFileRestored(file: BackupFile, bytesWritten: Long, tag: String) {
        filesRestored++
        n.updateRestoreNotification(filesRestored + filesWithError, totalFiles)
    }

    override fun onFileRestoreError(file: BackupFile, e: Exception) {
        filesWithError++
        n.updateRestoreNotification(filesRestored + filesWithError, totalFiles)
    }

    override fun onRestoreComplete(restoreDuration: Long) {
        n.showRestoreCompleteNotification(
            restored = filesRestored,
            duplicates = filesRemovedAsDuplicates,
            errors = filesWithError,
            total = totalFiles,
            intent = getRestoreCompleteIntent(),
        )
    }

    protected open fun getRestoreCompleteIntent(): PendingIntent? {
        return null
    }

}
