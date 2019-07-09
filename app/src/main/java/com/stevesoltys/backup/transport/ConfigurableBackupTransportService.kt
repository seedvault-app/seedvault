package com.stevesoltys.backup.transport

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

private val TAG = ConfigurableBackupTransportService::class.java.simpleName

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class ConfigurableBackupTransportService : Service() {

    private var transport: ConfigurableBackupTransport? = null

    override fun onCreate() {
        super.onCreate()
        transport = ConfigurableBackupTransport(applicationContext)
        Log.d(TAG, "Service created.")
    }

    override fun onBind(intent: Intent): IBinder {
        val transport = this.transport ?: throw IllegalStateException()
        return transport.binder.apply {
            Log.d(TAG, "Transport bound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transport = null
        Log.d(TAG, "Service destroyed.")
    }

}
