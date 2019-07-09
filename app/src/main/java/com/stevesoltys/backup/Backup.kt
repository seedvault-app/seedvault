package com.stevesoltys.backup

import android.app.Application
import android.app.backup.IBackupManager
import android.content.Context.BACKUP_SERVICE
import android.os.ServiceManager.getService

const val JOB_ID_BACKGROUND_BACKUP = 1

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class Backup : Application() {

    companion object {
        val backupManager: IBackupManager by lazy {
            IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE))
        }
    }

}
