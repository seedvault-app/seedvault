package com.stevesoltys.seedvault

import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.spyk
import org.koin.core.module.Module
import org.koin.dsl.module

private val spyBackupNotificationManager = spyk(
    BackupNotificationManager(
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
    )
)

class KoinInstrumentationTestApp : App() {

    override fun appModules(): List<Module> {
        val testModule = module {
            single { spyBackupNotificationManager }

            single {
                spyk(
                    RestoreViewModel(
                        this@KoinInstrumentationTestApp,
                        get(), get(), get(), get(), get(), get()
                    )
                )
            }
        }

        return super.appModules().plus(testModule)
    }
}
