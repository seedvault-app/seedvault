/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.stevesoltys.seedvault.service.file.backup

import org.calyxos.backup.storage.backup.BackupJobService

/*
test and debug with

  adb shell dumpsys jobscheduler |
  grep -A 23 -B 4 "Service: com.stevesoltys.seedvault/.storage.StorageBackupJobService"

force running with:

  adb shell cmd jobscheduler run -f com.stevesoltys.seedvault 0

 */
internal class FileBackupJobService : BackupJobService(FileBackupService::class.java)
