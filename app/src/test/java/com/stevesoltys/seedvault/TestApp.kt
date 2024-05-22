/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import com.stevesoltys.seedvault.crypto.CipherFactory
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.headerModule
import com.stevesoltys.seedvault.metadata.metadataModule
import com.stevesoltys.seedvault.plugins.saf.storagePluginModuleSaf
import com.stevesoltys.seedvault.restore.install.installModule
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.transport.backup.backupModule
import com.stevesoltys.seedvault.transport.restore.restoreModule
import io.mockk.mockk
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class TestApp : App() {

    override val isTest: Boolean = true

    private val testCryptoModule = module {
        factory<CipherFactory> { CipherFactoryImpl(get()) }
        single<KeyManager> { KeyManagerTestImpl() }
        single<Crypto> { CryptoImpl(get(), get(), get()) }
    }
    private val packageService: PackageService = mockk()
    private val appModule = module {
        single { Clock() }
        single { SettingsManager(this@TestApp) }
        single<PackageService> { packageService }
    }

    override fun startKoin(): KoinApplication {
        stopKoin()
        return startKoin {
            androidContext(this@TestApp)
            modules(
                listOf(
                    testCryptoModule,
                    headerModule,
                    metadataModule,
                    storagePluginModuleSaf, // storage plugin
                    backupModule,
                    restoreModule,
                    installModule,
                    appModule
                )
            )
        }
    }
}
