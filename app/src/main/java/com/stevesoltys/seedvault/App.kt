package com.stevesoltys.seedvault

import android.app.Application
import android.app.backup.BackupManager.PACKAGE_MANAGER_SENTINEL
import android.app.backup.IBackupManager
import android.content.Context.BACKUP_SERVICE
import android.os.Build
import android.os.ServiceManager.getService
import com.stevesoltys.seedvault.crypto.cryptoModule
import com.stevesoltys.seedvault.header.headerModule
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.metadataModule
import com.stevesoltys.seedvault.plugins.saf.documentsProviderModule
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.SettingsViewModel
import com.stevesoltys.seedvault.transport.backup.backupModule
import com.stevesoltys.seedvault.transport.restore.restoreModule
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.recoverycode.RecoveryCodeViewModel
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class App : Application() {

    private val appModule = module {
        single { SettingsManager(this@App) }
        single { BackupNotificationManager(this@App) }
        single { Clock() }
        factory<IBackupManager> { IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE)) }

        viewModel { SettingsViewModel(this@App, get(), get(), get(), get(), get()) }
        viewModel { RecoveryCodeViewModel(this@App, get()) }
        viewModel { BackupStorageViewModel(this@App, get(), get(), get()) }
        viewModel { RestoreStorageViewModel(this@App, get(), get()) }
        viewModel { RestoreViewModel(this@App, get(), get(), get(), get(), get()) }
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                listOf(
                    cryptoModule,
                    headerModule,
                    metadataModule,
                    documentsProviderModule,  // storage plugin
                    backupModule,
                    restoreModule,
                    appModule
                )
            )
        }
        migrateTokenFromMetadataToSettingsManager()
    }

    private val settingsManager: SettingsManager by inject()
    private val metadataManager: MetadataManager by inject()

    /**
     * The responsibility for the current token was moved to the [SettingsManager]
     * in the end of 2020.
     * This method migrates the token for existing installs and can be removed
     * after sufficient time has passed.
     */
    private fun migrateTokenFromMetadataToSettingsManager() {
        val token = metadataManager.getBackupToken()
        if (token != 0L && settingsManager.getToken() == null) {
            settingsManager.setNewToken(token)
        }
    }

}

const val MAGIC_PACKAGE_MANAGER = PACKAGE_MANAGER_SENTINEL
const val ANCESTRAL_RECORD_KEY = "@ancestral_record@"
const val GLOBAL_METADATA_KEY = "@meta@"

fun isDebugBuild() = Build.TYPE == "userdebug"
