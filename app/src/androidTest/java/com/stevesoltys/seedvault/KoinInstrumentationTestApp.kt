package com.stevesoltys.seedvault

import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.transport.backup.FullBackup
import com.stevesoltys.seedvault.transport.backup.InputFactory
import com.stevesoltys.seedvault.transport.backup.KVBackup
import com.stevesoltys.seedvault.transport.restore.FullRestore
import com.stevesoltys.seedvault.transport.restore.KVRestore
import com.stevesoltys.seedvault.transport.restore.OutputFactory
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import io.mockk.spyk
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

internal var currentRestoreViewModel: RestoreViewModel? = null
internal var currentBackupStorageViewModel: BackupStorageViewModel? = null
internal var currentRestoreStorageViewModel: RestoreStorageViewModel? = null

class KoinInstrumentationTestApp : App() {

    override fun appModules(): List<Module> {
        val testModule = module {
            val context = this@KoinInstrumentationTestApp

            single { spyk(BackupNotificationManager(context)) }
            single { spyk(FullBackup(get(), get(), get(), get())) }
            single { spyk(KVBackup(get(), get(), get(), get(), get())) }
            single { spyk(InputFactory()) }

            single { spyk(FullRestore(get(), get(), get(), get(), get())) }
            single { spyk(KVRestore(get(), get(), get(), get(), get(), get())) }
            single { spyk(OutputFactory()) }

            viewModel {
                currentRestoreViewModel =
                    spyk(RestoreViewModel(context, get(), get(), get(), get(), get(), get()))
                currentRestoreViewModel!!
            }

            viewModel {
                currentBackupStorageViewModel =
                    spyk(BackupStorageViewModel(context, get(), get(), get(), get()))
                currentBackupStorageViewModel!!
            }

            viewModel {
                currentRestoreStorageViewModel =
                    spyk(RestoreStorageViewModel(context, get(), get()))
                currentRestoreStorageViewModel!!
            }
        }

        return super.appModules().plus(testModule)
    }
}
