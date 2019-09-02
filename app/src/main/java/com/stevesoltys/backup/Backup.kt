package com.stevesoltys.backup

import android.Manifest.permission.READ_PHONE_STATE
import android.app.Application
import android.app.backup.IBackupManager
import android.content.Context.BACKUP_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.os.ServiceManager.getService
import android.util.Log
import com.stevesoltys.backup.crypto.KeyManager
import com.stevesoltys.backup.crypto.KeyManagerImpl
import com.stevesoltys.backup.settings.getDeviceName
import com.stevesoltys.backup.settings.setDeviceName
import io.github.novacrypto.hashing.Sha256.sha256Twice

private const val URI_AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents"

private val TAG = Backup::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class Backup : Application() {

    companion object {
        val backupManager: IBackupManager by lazy {
            IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE))
        }
        val keyManager: KeyManager by lazy {
            KeyManagerImpl()
        }
    }

    val notificationManager by lazy {
        BackupNotificationManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        storeDeviceName()
    }

    private fun storeDeviceName() {
        if (getDeviceName(this) != null) return  // we already have a stored device name

        val permission = READ_PHONE_STATE
        if (checkSelfPermission(permission) != PERMISSION_GRANTED) {
            throw AssertionError("You need to grant the $permission permission.")
        }
        // TODO consider just using a hash for the entire device name and store metadata in an encrypted file
        val id = sha256Twice(Build.getSerial().toByteArray(Utf8))
                .copyOfRange(0, 8)
                .encodeBase64()
        val name = "${Build.MANUFACTURER} ${Build.MODEL} ($id)"
        Log.i(TAG, "Initialized device name to: $name")
        setDeviceName(this, name)
    }

}

fun Uri.isOnExternalStorage() = authority == URI_AUTHORITY_EXTERNAL_STORAGE
