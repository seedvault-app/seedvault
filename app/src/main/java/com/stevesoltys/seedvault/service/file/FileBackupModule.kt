/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.service.file

import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.StoragePlugin
import org.koin.dsl.module

val filesModule = module {
    single<StoragePlugin> { FileBackupStoragePlugin(get(), get(), get()) }
    single { StorageBackup(get(), get()) }
}
